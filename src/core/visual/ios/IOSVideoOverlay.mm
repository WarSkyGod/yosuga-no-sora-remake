#import <AVFoundation/AVFoundation.h>
#import <objc/runtime.h>
#import <QuartzCore/QuartzCore.h>
#import <UIKit/UIKit.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>

#include "MacVideoOverlay.h"

static __weak UIWindowScene *TVPIOSApplicationWindowScene;

static BOOL TVPIOSIsSDLWindow(UIWindow *window)
{
    Class sdlWindowClass = NSClassFromString(@"SDL_uikitwindow");
    return window && sdlWindowClass && [window isKindOfClass:sdlWindowClass];
}

static UIWindow *TVPIOSFindSDLWindow(UIWindowScene *scene)
{
    for(UIWindow *window in scene.windows) {
        if(TVPIOSIsSDLWindow(window)) return window;
    }
    return nil;
}

static void TVPIOSRelayoutSDLWindow(UIWindowScene *scene)
{
    if(!scene) return;
    if(!NSThread.isMainThread) {
        __weak UIWindowScene *weakScene = scene;
        dispatch_async(dispatch_get_main_queue(), ^{
            TVPIOSRelayoutSDLWindow(weakScene);
        });
        return;
    }

    UIWindow *window = TVPIOSFindSDLWindow(scene);
    if(!window) return;

    /*
     * When another application is portrait-only, UIKit can temporarily lay
     * out SDL's view using the portrait scene size while this application is
     * in the background.  The old SDL UIKit backend does not always receive a
     * final landscape layout pass on return, leaving its Metal drawable at the
     * small portrait-derived size.  Reattach every level of the SDL view tree
     * to the scene's current coordinate space and force that final pass.
     */
    CGRect sceneBounds = scene.coordinateSpace.bounds;
    if(CGRectIsEmpty(sceneBounds)) return;

    /* Use CEILING so the window always covers the whole scene: a fractional
         * height (e.g. 1366.5 pt) must round UP, not down - flooring would make
         * the window 1px shorter and leave the exact bottom-edge line this is
         * meant to kill. */
        sceneBounds = CGRectMake(floor(sceneBounds.origin.x), floor(sceneBounds.origin.y),
                                 ceil(sceneBounds.size.width), ceil(sceneBounds.size.height));

    window.frame = sceneBounds;
    UIView *contentView = window.rootViewController.view;
    if(contentView) {
        contentView.frame = window.bounds;
        [contentView setNeedsLayout];
        [contentView layoutIfNeeded];
        /* Force the actual SDL rendering view (Metal/GL) to the same bounds:
         * layoutIfNeeded alone can leave it at the old size until the next
         * interaction, which is exactly the "title screen bottom gap" that
         * disappears after switching to settings. */
        for(UIView *sub in contentView.subviews) {
            Class metalViewClass = NSClassFromString(@"SDL_uikitmetalview");
            Class openglViewClass = NSClassFromString(@"SDL_uikitopenglview");
            BOOL isRenderView = (metalViewClass && [sub isKindOfClass:metalViewClass])
                             || (openglViewClass && [sub isKindOfClass:openglViewClass]);
            if(isRenderView) {
                sub.frame = contentView.bounds;
                [sub layoutIfNeeded];
            }
        }
    }
    [window setNeedsLayout];
    [window layoutIfNeeded];
}

static void TVPIOSScheduleSDLWindowRelayout(UIWindowScene *scene)
{
    if(!scene) return;
    TVPIOSRelayoutSDLWindow(scene);

    /* Run once after UIKit has committed the foreground/orientation change. */
    __weak UIWindowScene *weakScene = scene;
    dispatch_async(dispatch_get_main_queue(), ^{
        TVPIOSRelayoutSDLWindow(weakScene);
    });
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
        (int64_t)(0.25 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        TVPIOSRelayoutSDLWindow(weakScene);
    });
    /* Late pass: the engine's title screen can be laid out after the earlier
       passes, leaving a sub-pixel gap at the bottom edge until the first
       interaction triggers another layout.  A final pass covers it. */
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
        (int64_t)(0.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        TVPIOSRelayoutSDLWindow(weakScene);
    });
    /* The title screen (and its background surface) can appear well after
       the 0.5s pass; keep running late passes so the bottom edge is covered
       even with a slow data mount or slow first frame. */
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
        (int64_t)(1.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        TVPIOSRelayoutSDLWindow(weakScene);
    });
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
        (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        TVPIOSRelayoutSDLWindow(weakScene);
    });
}

/* ------------------------------------------------------------------ *
 * Background behaviour                                                 *
 * No UIBackgroundModes=audio, no silent keep-alive queue: when the app *
 * goes to the background the engine audio (FAudio) is suspended so     *
 * BGM/SE stop instead of playing in the background, and any AVPlayer   *
 * (OP movie) is paused too.  On return we resume both and reactivate   *
 * the shared audio session, so the game carries on where it left off   *
 * instead of freezing or coming back muted.                            *
 * ------------------------------------------------------------------ */

/* Implemented in FAudioDevice.cpp: freeze/unfreeze the FAudio engine. */
extern "C" void TVPIOSAudioSuspend(void);
extern "C" void TVPIOSAudioResume(void);

static void TVPIOSReactivateAudioSession(void)
{
    /* Belt-and-braces: make sure the shared session is active for the SDL
     * audio backend after any lifecycle transition (interruptions from
     * alarms/calls, background suspension, ...).  SDL installs its own
     * interruption listener, but an inactive session is not an
     * interruption - without this the game would come back from the
     * background with working rendering but silent audio. */
    NSError *error = nil;
    AVAudioSession *session = [AVAudioSession sharedInstance];
    if(![session setActive:YES error:&error])
        NSLog(@"krkrsdl2: session reactivate failed: %@",
              error.localizedDescription ?: @"unknown");
}

/* Documents/<bundle>/savedata directory (UTF-8, no trailing slash).  Used
 * by the engine to relocate saves to a user-reachable folder; see
 * ApplicationSpecialPath.h.  Old saves are not auto-migrated here; a fresh
 * install simply starts using the new location. */
extern "C" const char *TVPIOSGetDocumentsDirectory(void)
{
    /* Place saves under a game-specific subfolder so they never collide
       with the saves of a different krkrsdl2 application sharing the same
       Documents/ directory.  The bundle identifier is unique per app;
       fall back to a fixed product name when it is unavailable. */
    static std::string cached;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSArray *paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *documents = paths.firstObject;
        if(documents && documents.length > 0)
        {
            NSString *folder = NSBundle.mainBundle.bundleIdentifier;
            if(folder.length == 0)
                folder = @"YosugaSoraHD";
            NSString *saveDir = [documents stringByAppendingPathComponent:folder];
            saveDir = [saveDir stringByAppendingPathComponent:@"savedata"];
            [[NSFileManager defaultManager] createDirectoryAtPath:saveDir
                                      withIntermediateDirectories:YES
                                                       attributes:nil
                                                            error:nil];
            const char *utf8 = saveDir.fileSystemRepresentation;
            if(utf8) cached = utf8;
        }
    });
    return cached.empty() ? NULL : cached.c_str();
}

@interface TVPIOSSceneDelegate : UIResponder <UIWindowSceneDelegate>
@end

@implementation TVPIOSSceneDelegate
- (void)scene:(UIScene *)scene
    willConnectToSession:(UISceneSession *)session
             options:(UISceneConnectionOptions *)connectionOptions
{
    (void)session;
    (void)connectionOptions;
    if([scene isKindOfClass:UIWindowScene.class]) {
        TVPIOSApplicationWindowScene = (UIWindowScene *)scene;
        TVPIOSScheduleSDLWindowRelayout((UIWindowScene *)scene);
        [self installLifecycleObservers];
    }
}

- (void)sceneDidEnterBackground:(UIScene *)scene
{
    (void)scene;
    /* No background audio: suspend the FAudio engine (BGM/SE stop) and let
       the OS suspend the app normally.  Active OP movies are paused by
       TVPIOSMovieController's own observers. */
    TVPIOSAudioSuspend();
}

- (void)sceneWillEnterForeground:(UIScene *)scene
{
    if([scene isKindOfClass:UIWindowScene.class])
        TVPIOSScheduleSDLWindowRelayout((UIWindowScene *)scene);
    /* Audio is resumed in sceneDidBecomeActive AFTER the audio session has
       been reactivated: starting the FAudio engine earlier would leave the
       mixer running against an inactive session, which adds a gap when the
       session finally activates. */
}

- (void)sceneDidBecomeActive:(UIScene *)scene
{
    if([scene isKindOfClass:UIWindowScene.class])
        TVPIOSScheduleSDLWindowRelayout((UIWindowScene *)scene);
    /* Reactivate the shared session first, then unfreeze FAudio so voice
       playback resumes with the session already live (no first-frame gap). */
    TVPIOSReactivateAudioSession();
    TVPIOSAudioResume();
}

- (void)installLifecycleObservers
{
    /* Belt-and-braces for older iOS versions / non-scene entry paths:
       listen to the application-level background/foreground notifications
       as well, so suspend/resume is driven regardless of which lifecycle
       path iOS uses. */
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        [nc addObserverForName:UIApplicationDidEnterBackgroundNotification
                        object:nil queue:NSOperationQueue.mainQueue
                    usingBlock:^(NSNotification *note) {
                        (void)note;
                        TVPIOSAudioSuspend();
                    }];
        [nc addObserverForName:UIApplicationWillEnterForegroundNotification
                        object:nil queue:NSOperationQueue.mainQueue
                    usingBlock:^(NSNotification *note) {
                        (void)note;
                        /* Audio resumes in DidBecomeActive after the session
                           is reactivated (see sceneDidBecomeActive). */
                    }];
        [nc addObserverForName:UIApplicationDidBecomeActiveNotification
                        object:nil queue:NSOperationQueue.mainQueue
                    usingBlock:^(NSNotification *note) {
                        (void)note;
                        TVPIOSReactivateAudioSession();
                        TVPIOSAudioResume();
                    }];
    });
}

- (void)windowScene:(UIWindowScene *)windowScene
    didUpdateCoordinateSpace:(id<UICoordinateSpace>)previousCoordinateSpace
          interfaceOrientation:(UIInterfaceOrientation)previousInterfaceOrientation
             traitCollection:(UITraitCollection *)previousTraitCollection
{
    (void)previousCoordinateSpace;
    (void)previousInterfaceOrientation;
    (void)previousTraitCollection;
    TVPIOSScheduleSDLWindowRelayout(windowScene);
}
@end

/*
 * SDL 2 creates its UIKit window with initWithFrame:.  A window created that
 * way is not associated with a UIWindowScene, which leaves the application
 * rendering off-screen on scene-only versions of UIKit.  Keep this bridge in
 * the application so the SDL submodule remains unmodified and older iOS
 * versions continue to use SDL's normal lifecycle.
 */
@interface UIWindow (TVPIOSSceneCompatibility)
- (void)tvp_makeKeyAndVisible;
@end

@implementation UIWindow (TVPIOSSceneCompatibility)
+ (void)load
{
    if(@available(iOS 13.0, *)) {
        static dispatch_once_t onceToken;
        dispatch_once(&onceToken, ^{
            Method original = class_getInstanceMethod(self,
                @selector(makeKeyAndVisible));
            Method replacement = class_getInstanceMethod(self,
                @selector(tvp_makeKeyAndVisible));
            method_exchangeImplementations(original, replacement);
        });
    }
}

- (void)tvp_makeKeyAndVisible
{
    if(@available(iOS 13.0, *)) {
        UIWindowScene *scene = TVPIOSApplicationWindowScene;
        if(!self.windowScene && scene && TVPIOSIsSDLWindow(self))
            self.windowScene = scene;
    }
    [self tvp_makeKeyAndVisible];
    if(@available(iOS 13.0, *)) {
        if(TVPIOSIsSDLWindow(self))
            TVPIOSScheduleSDLWindowRelayout(self.windowScene);
    }
}
@end

@class TVPIOSMovieController;

@interface TVPIOSMovieController : NSObject
- (instancetype)initWithPath:(NSString *)path
                      window:(UIWindow *)window
                     context:(void *)context
                    finished:(TVPMacVideoFinishedCallback)finished;
- (void)shutdown;
- (void)requestSkip;
- (void)play;
- (void)pause;
- (void)stop;
- (void)rewind;
- (void)setMovieBoundsLeft:(int)left top:(int)top width:(int)width height:(int)height;
- (void)setScreenGeometryWidth:(int)width height:(int)height;
- (void)movieViewDidLayout;
- (void)setMovieVisible:(BOOL)visible;
- (void)setMovieVolume:(float)volume;
- (void)setMovieRate:(float)rate;
- (void)setMovieTime:(double)seconds;
- (float)movieVolume;
- (float)movieRate;
- (BOOL)hasAudio;
- (int)videoWidth;
- (int)videoHeight;
- (double)frameRate;
- (double)duration;
- (double)currentTime;
@end

@interface TVPIOSMovieView : UIView
@property(nonatomic, strong) AVPlayerLayer *movieLayer;
@property(nonatomic, weak) TVPIOSMovieController *controller;
@end

@implementation TVPIOSMovieView
- (void)layoutSubviews
{
    [super layoutSubviews];
    self.movieLayer.frame = self.bounds;
    [self.controller movieViewDidLayout];
}
@end

@implementation TVPIOSMovieController {
    AVURLAsset *_asset;
    AVPlayerItem *_item;
    AVPlayer *_player;
    AVPlayerLayer *_playerLayer;
    TVPIOSMovieView *_view;
    __weak UIView *_contentView;
    id _endObserver;
    id _failureObserver;
    void *_context;
    TVPMacVideoFinishedCallback _finished;
    BOOL _playing;
    BOOL _ended;
    BOOL _hasAudio;
    float _volume;
    float _rate;
    int _gameWidth;
    int _gameHeight;
    int _left;
    int _top;
    int _width;
    int _height;
    BOOL _hasBounds;
    BOOL _retriedOnce;
    BOOL _skipRequested;
    /* True when we paused because the app went to the background; resume
       automatically on return so the OP keeps playing. */
    BOOL _resumeAfterForeground;
    id _backgroundObserver;
    id _activeObserver;
    UILongPressGestureRecognizer *_skipGesture;
}

- (instancetype)initWithPath:(NSString *)path
                      window:(UIWindow *)window
                     context:(void *)context
                    finished:(TVPMacVideoFinishedCallback)finished
{
    self = [super init];
    if(!self || path.length == 0 ||
       ![[NSFileManager defaultManager] isReadableFileAtPath:path]) return nil;

    /* Make sure the audio session allows playback; without an active
       playback session AVPlayer can fail shortly after starting. */
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    [audioSession setCategory:AVAudioSessionCategoryPlayback error:nil];
    [audioSession setActive:YES error:nil];

    NSURL *url = [NSURL fileURLWithPath:path isDirectory:NO];
    _asset = [AVURLAsset URLAssetWithURL:url options:nil];
    if(!_asset.playable || _asset.hasProtectedContent) return nil;

    _context = context;
    _finished = finished;
    _volume = 1.0f;
    _rate = 1.0f;
    _gameWidth = 0;
    _gameHeight = 0;
    _left = 0;
    _top = 0;
    _width = 0;
    _height = 0;
    _hasBounds = NO;
    _retriedOnce = NO;
    _skipRequested = NO;
    _item = [AVPlayerItem playerItemWithAsset:_asset];
    _player = [AVPlayer playerWithPlayerItem:_item];
    _player.actionAtItemEnd = AVPlayerActionAtItemEndPause;
    _player.volume = _volume;
    _hasAudio = [_asset tracksWithMediaType:AVMediaTypeAudio].count > 0;

    window = window ?: UIApplication.sharedApplication.keyWindow;
    _contentView = window.rootViewController.view ?: window;
    if(!_contentView) return nil;

    _view = [[TVPIOSMovieView alloc] initWithFrame:_contentView.bounds];
    _view.autoresizingMask = UIViewAutoresizingFlexibleWidth |
        UIViewAutoresizingFlexibleHeight;
    _view.backgroundColor = UIColor.blackColor;
    _view.opaque = YES;
    _view.userInteractionEnabled = NO;

    _playerLayer = [AVPlayerLayer playerLayerWithPlayer:_player];
    _playerLayer.frame = _view.bounds;
    _playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    _view.movieLayer = _playerLayer;
    _view.controller = self;
    [_view.layer addSublayer:_playerLayer];
    [_contentView addSubview:_view];

    /* Long-press anywhere in the movie area skips the current animation
       (opening/ending movies and gallery previews).  The engine treats it
       as playback having finished; the overlay only observes the gesture
       so it never consumes touches itself. */
    _skipGesture = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(handleSkipLongPress:)];
    _skipGesture.minimumPressDuration = 0.6;
    _skipGesture.cancelsTouchesInView = NO;
    _skipGesture.delaysTouchesBegan = NO;
    _skipGesture.delaysTouchesEnded = NO;
    [_contentView addGestureRecognizer:_skipGesture];

    NSNotificationCenter *notifications = NSNotificationCenter.defaultCenter;
    __weak TVPIOSMovieController *weakSelf = self;
    _endObserver = [notifications
        addObserverForName:AVPlayerItemDidPlayToEndTimeNotification
                    object:_item
                     queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *notification) {
                    (void)notification;
                    TVPIOSMovieController *strongSelf = weakSelf;
                    if(!strongSelf) return;
                    strongSelf->_playing = NO;
                    strongSelf->_ended = YES;
                    if(!strongSelf->_retriedOnce) {
                        double played = CMTimeGetSeconds(strongSelf->_player.currentTime);
                        double expected = [strongSelf duration];
                        if(expected > 2.0 && played < 3.0 && played < expected * 0.5) {
                            /* Ending after only a second or two smells like a
                               bogus duration read or a transient glitch; retry
                               once before reporting completion. */
                            strongSelf->_retriedOnce = YES;
                            NSLog(@"krkrsdl2: iOS movie ended prematurely (%.2f/%.2f s); retrying once.",
                                  played, expected);
                            [strongSelf->_player seekToTime:kCMTimeZero
                                toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
                            strongSelf->_ended = NO;
                            strongSelf->_playing = YES;
                            [strongSelf->_player play];
                            strongSelf->_player.rate = strongSelf->_rate;
                            return;
                        }
                    }
                    if(strongSelf->_finished)
                        strongSelf->_finished(strongSelf->_context);
                }];
    _failureObserver = [notifications
        addObserverForName:AVPlayerItemFailedToPlayToEndTimeNotification
                    object:_item
                     queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *notification) {
                    NSError *error = notification.userInfo[AVPlayerItemFailedToPlayToEndTimeErrorKey];
                    NSLog(@"krkrsdl2: iOS movie playback failed: %@",
                          error.localizedDescription ?: @"unknown error");
                    TVPIOSMovieController *strongSelf = weakSelf;
                    if(!strongSelf) return;
                    if(!strongSelf->_retriedOnce) {
                        /* Transient failures (audio session handover, first
                           frame decode) often recover on a clean restart. */
                        strongSelf->_retriedOnce = YES;
                        NSLog(@"krkrsdl2: iOS movie playback retrying once.");
                        [strongSelf->_player seekToTime:kCMTimeZero
                            toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
                        strongSelf->_ended = NO;
                        strongSelf->_playing = YES;
                        [strongSelf->_player play];
                        strongSelf->_player.rate = strongSelf->_rate;
                        return;
                    }
                    strongSelf->_playing = NO;
                    if(strongSelf->_finished)
                        strongSelf->_finished(strongSelf->_context);
                }];

    /* Pause on background, resume on return: the OP must keep playing
       (video and audio together) after coming back from another app,
       instead of staying frozen at the last rendered frame. */
    _backgroundObserver = [notifications
        addObserverForName:UIApplicationDidEnterBackgroundNotification
                    object:nil queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *notification) {
                    (void)notification;
                    TVPIOSMovieController *strongSelf = weakSelf;
                    if(!strongSelf) return;
                    if(strongSelf->_playing && !strongSelf->_ended &&
                       !strongSelf->_skipRequested) {
                        strongSelf->_resumeAfterForeground = YES;
                        [strongSelf->_player pause];
                        strongSelf->_playing = NO;
                    }
                }];
    _activeObserver = [notifications
        addObserverForName:UIApplicationDidBecomeActiveNotification
                    object:nil queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *notification) {
                    (void)notification;
                    TVPIOSMovieController *strongSelf = weakSelf;
                    if(!strongSelf || !strongSelf->_resumeAfterForeground) return;
                    strongSelf->_resumeAfterForeground = NO;
                    if(strongSelf->_ended || strongSelf->_skipRequested) return;
                    strongSelf->_playing = YES;
                    [strongSelf->_player play];
                    strongSelf->_player.rate = strongSelf->_rate;
                }];
    return self;
}

- (AVAssetTrack *)videoTrack
{
    return [_asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
}

- (void)play
{
    if(!_player || _playing) return;
    if(_ended) [self rewind];
    _skipRequested = NO; // allow skipping again on replay/loop
    _playing = YES;
    [_player play];
    _player.rate = _rate;
}

- (void)pause { [_player pause]; _playing = NO; }

- (void)stop
{
    [_player pause];
    [_player seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero
         toleranceAfter:kCMTimeZero];
    _playing = NO;
    _ended = NO;
}

- (void)handleSkipLongPress:(UILongPressGestureRecognizer *)gesture
{
    if(gesture.state != UIGestureRecognizerStateBegan) return;
    [self requestSkip];
}

- (void)requestSkip
{
    if(!_player) return;
    if(_skipRequested) return;
    if(!_playing) return;
    _skipRequested = YES;
    _playing = NO;
    [_player pause];
    if(_finished)
        _finished(_context);
}

- (void)rewind
{
    [_player seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero
         toleranceAfter:kCMTimeZero];
    _ended = NO;
    if(_playing) _player.rate = _rate;
}

- (void)setMovieBoundsLeft:(int)left top:(int)top width:(int)width height:(int)height
{
    _left = left;
    _top = top;
    _width = std::max(width, 0);
    _height = std::max(height, 0);
    _hasBounds = YES;
    _view.autoresizingMask = UIViewAutoresizingNone;
    [self updateMovieFrame];
}

- (void)setScreenGeometryWidth:(int)width height:(int)height
{
    _gameWidth = width;
    _gameHeight = height;
    if(_hasBounds) [self updateMovieFrame];
}

- (void)movieViewDidLayout
{
    if(_hasBounds) [self updateMovieFrame];
}

- (void)updateMovieFrame
{
    if(!_view || !_contentView || !_hasBounds) return;

    CGSize viewSize = _contentView.bounds.size;
    CGRect frame;
    if(_gameWidth > 0 && _gameHeight > 0 &&
       viewSize.width > 0.0 && viewSize.height > 0.0)
    {
        /*
         * The engine reports overlay rectangles in game-space units, and the
         * renderer fits the game into the hosting view with a uniform
         * aspect-ratio scale plus centering offsets.  Reproduce that exact
         * transform here.  This stays correct even when UIScreen does not
         * describe the hosting window (for example inside app containers),
         * unlike the previous UIScreen-scale based mapping.
         */
        double scale = std::min(viewSize.width / (double)_gameWidth,
                                viewSize.height / (double)_gameHeight);
        double offsetX = (viewSize.width - _gameWidth * scale) / 2.0;
        double offsetY = (viewSize.height - _gameHeight * scale) / 2.0;
        frame = CGRectMake((CGFloat)(offsetX + _left * scale),
                           (CGFloat)(offsetY + _top * scale),
                           (CGFloat)(_width * scale),
                           (CGFloat)(_height * scale));
    }
    else
    {
        /* Legacy fallback when the engine never provided the geometry. */
        CGFloat scale = UIScreen.mainScreen.scale;
        if(scale <= 0.0) scale = 1.0;
        frame = CGRectMake(_left / scale, _top / scale,
                           _width / scale, _height / scale);
    }
    frame = CGRectIntegral(frame);
    if(!CGRectEqualToRect(_view.frame, frame))
        _view.frame = frame;
}

- (void)setMovieVisible:(BOOL)visible { _view.hidden = !visible; }
- (void)setMovieVolume:(float)volume
{
    _volume = std::max(0.0f, std::min(volume, 1.0f));
    _player.volume = _volume;
}
- (void)setMovieRate:(float)rate
{
    _rate = std::max(rate, 0.01f);
    if(_playing) _player.rate = _rate;
}
- (void)setMovieTime:(double)seconds
{
    if(!_player || !std::isfinite(seconds)) return;
    double movieDuration = self.duration;
    seconds = std::max(seconds, 0.0);
    if(movieDuration > 0.0) seconds = std::min(seconds, movieDuration);
    [_player seekToTime:CMTimeMakeWithSeconds(seconds, 600)
        toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
    _ended = NO;
}
- (float)movieVolume { return _volume; }
- (float)movieRate { return _rate; }
- (BOOL)hasAudio { return _hasAudio; }
- (int)videoWidth
{
    AVAssetTrack *track = self.videoTrack;
    if(!track) return 0;
    CGRect rect = CGRectApplyAffineTransform(
        CGRectMake(0, 0, track.naturalSize.width, track.naturalSize.height),
        track.preferredTransform);
    return (int)std::lround(std::fabs(rect.size.width));
}
- (int)videoHeight
{
    AVAssetTrack *track = self.videoTrack;
    if(!track) return 0;
    CGRect rect = CGRectApplyAffineTransform(
        CGRectMake(0, 0, track.naturalSize.width, track.naturalSize.height),
        track.preferredTransform);
    return (int)std::lround(std::fabs(rect.size.height));
}
- (double)frameRate
{
    AVAssetTrack *track = self.videoTrack;
    if(!track) return 0.0;
    if(track.nominalFrameRate > 0.0f) return track.nominalFrameRate;
    double frameDuration = CMTimeGetSeconds(track.minFrameDuration);
    return std::isfinite(frameDuration) && frameDuration > 0.0
        ? 1.0 / frameDuration : 0.0;
}
- (double)duration
{
    CMTime time = _item.duration;
    if(!CMTIME_IS_NUMERIC(time)) time = _asset.duration;
    double seconds = CMTimeGetSeconds(time);
    return std::isfinite(seconds) && seconds > 0.0 ? seconds : 0.0;
}
- (double)currentTime
{
    double seconds = CMTimeGetSeconds(_player.currentTime);
    return std::isfinite(seconds) && seconds > 0.0 ? seconds : 0.0;
}

- (void)shutdown
{
    _playing = NO;
    [_player pause];
    if(_skipGesture)
    {
        if(_contentView)
            [_contentView removeGestureRecognizer:_skipGesture];
        _skipGesture = nil;
    }
    NSNotificationCenter *notifications = NSNotificationCenter.defaultCenter;
    if(_endObserver) [notifications removeObserver:_endObserver];
    if(_failureObserver) [notifications removeObserver:_failureObserver];
    if(_backgroundObserver) [notifications removeObserver:_backgroundObserver];
    if(_activeObserver) [notifications removeObserver:_activeObserver];
    _endObserver = nil;
    _failureObserver = nil;
    _backgroundObserver = nil;
    _activeObserver = nil;
    _resumeAfterForeground = NO;
    _playerLayer.player = nil;
    [_playerLayer removeFromSuperlayer];
    [_view removeFromSuperview];
    _view.movieLayer = nil;
    _playerLayer = nil;
    _view = nil;
    _contentView = nil;
    _player = nil;
    _item = nil;
    _asset = nil;
    _finished = nullptr;
    _context = nullptr;
}
- (void)dealloc { [self shutdown]; }
@end

struct TVPIOSVideoHandle { __strong TVPIOSMovieController *controller; };

static TVPIOSMovieController *TVPIOSController(void *handle)
{
    return handle ? static_cast<TVPIOSVideoHandle *>(handle)->controller : nil;
}

void *TVPMacVideoCreate(const char *path, void *nativeWindow, void *context,
                        TVPMacVideoFinishedCallback finished)
{
    if(!path) return nullptr;
    NSString *moviePath = [[NSString alloc] initWithUTF8String:path];
    UIWindow *window = (__bridge UIWindow *)nativeWindow;
    TVPIOSMovieController *controller = [[TVPIOSMovieController alloc]
        initWithPath:moviePath window:window context:context finished:finished];
    if(!controller) return nullptr;
    TVPIOSVideoHandle *handle = new TVPIOSVideoHandle();
    handle->controller = controller;
    return handle;
}

void TVPMacVideoDestroy(void *handle)
{
    if(!handle) return;
    TVPIOSVideoHandle *movie = static_cast<TVPIOSVideoHandle *>(handle);
    [movie->controller shutdown];
    movie->controller = nil;
    delete movie;
}
void TVPMacVideoPlay(void *handle) { [TVPIOSController(handle) play]; }
void TVPMacVideoPause(void *handle) { [TVPIOSController(handle) pause]; }
void TVPMacVideoStop(void *handle) { [TVPIOSController(handle) stop]; }
void TVPMacVideoRewind(void *handle) { [TVPIOSController(handle) rewind]; }
void TVPMacVideoSetBounds(void *handle, int left, int top, int width, int height)
{
    [TVPIOSController(handle) setMovieBoundsLeft:left top:top width:width height:height];
}
void TVPMacVideoSetScreenGeometry(void *handle, int windowWidth, int windowHeight)
{
    [TVPIOSController(handle) setScreenGeometryWidth:windowWidth height:windowHeight];
}
void TVPMacVideoSetVisible(void *handle, int visible)
{
    [TVPIOSController(handle) setMovieVisible:visible != 0];
}
void TVPMacVideoSetVolume(void *handle, float volume)
{
    [TVPIOSController(handle) setMovieVolume:volume];
}
void TVPMacVideoSetRate(void *handle, float rate)
{
    [TVPIOSController(handle) setMovieRate:rate];
}
void TVPMacVideoSetTime(void *handle, double seconds)
{
    [TVPIOSController(handle) setMovieTime:seconds];
}
float TVPMacVideoGetVolume(void *handle) { return TVPIOSController(handle).movieVolume; }
float TVPMacVideoGetRate(void *handle) { return TVPIOSController(handle).movieRate; }
int TVPMacVideoHasAudio(void *handle) { return TVPIOSController(handle).hasAudio ? 1 : 0; }
int TVPMacVideoGetWidth(void *handle) { return TVPIOSController(handle).videoWidth; }
int TVPMacVideoGetHeight(void *handle) { return TVPIOSController(handle).videoHeight; }
double TVPMacVideoGetFPS(void *handle) { return TVPIOSController(handle).frameRate; }
double TVPMacVideoGetDuration(void *handle) { return TVPIOSController(handle).duration; }
double TVPMacVideoGetTime(void *handle) { return TVPIOSController(handle).currentTime; }
