/* SPDX-License-Identifier: MIT */
/*
 * Bridge between the OpenHarmony NAPI entry module (libentry.so) and the
 * vendored SDL2 OpenHarmony backend.
 *
 * The declarations in this header are implemented in two places:
 *   - SDL_ohosvideo.c         (libkrkrsdl2.so): touch event delivery AND the
 *     fullscreen/windowed request state (the engine's SDLApplication.cpp and
 *     its SDL video driver resolve those within their own .so at link time;
 *     libentry.so reaches them through the exported dynamic symbols).
 *   - krkrsdl2_ohos_entry.cpp  (libentry.so): files directory, XComponent
 *     surface state and the native window wait/query helpers.
 *   - SDL_ohosevents.c         (libSDL2 and libentry): touch event plumbing.
 *
 * tools/setup_ohos_project.py copies this header into the vendored SDL tree
 * so both sides compile against identical declarations.
 */

#ifndef SDL_OHOS_BRIDGE_H
#define SDL_OHOS_BRIDGE_H

/* Touch types delivered to SDL_OHOS_OnTouchEvent. */
#define SDL_OHOS_TOUCH_DOWN 0
#define SDL_OHOS_TOUCH_UP 1
#define SDL_OHOS_TOUCH_MOVE 2

/* The OpenHarmony NDK builds with -fvisibility=hidden; symbols that cross
 * the libentry.so / libkrkrsdl2.so boundary must be explicitly exported. */
#if defined(__OHOS__) && !defined(OHOS_EXPORT)
#if defined(__GNUC__) || defined(__clang__)
#define OHOS_EXPORT __attribute__((visibility("default")))
#else
#define OHOS_EXPORT
#endif
#elif !defined(OHOS_EXPORT)
#define OHOS_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Store the application sandbox files directory. */
OHOS_EXPORT void SDL_OHOS_SetFilesDir(const char *files_dir) __attribute__((weak));

/* Return the sandbox files directory, or NULL when not set yet. */
OHOS_EXPORT const char *SDL_OHOS_GetFilesDir(void) __attribute__((weak));

/* Store the external game-data directory (may live outside the sandbox,
 * e.g. the public Download folder). SDL_GetBasePath prefers it. */
OHOS_EXPORT void SDL_OHOS_SetDataDir(const char *data_dir) __attribute__((weak));

/* Return the external game-data directory, or NULL when not set. */
OHOS_EXPORT const char *SDL_OHOS_GetDataDir(void) __attribute__((weak));

/* Store the external savedata directory. SDL_GetPrefPath prefers it so the
 * save files stay user-accessible. */
OHOS_EXPORT void SDL_OHOS_SetSaveDir(const char *save_dir) __attribute__((weak));

/* Return the external savedata directory, or NULL when not set. */
OHOS_EXPORT const char *SDL_OHOS_GetSaveDir(void) __attribute__((weak));

/* Block until the XComponent surface provides a native window. Returns 1 when
 * the window is ready, 0 on timeout. */
OHOS_EXPORT int SDL_OHOS_WaitForNativeWindow(int timeout_ms) __attribute__((weak));

/* Return the OHNativeWindow, or NULL when the surface is not ready. */
OHOS_EXPORT void *SDL_OHOS_GetNativeWindow(void) __attribute__((weak));

/* Frame-scoped surface acquisition: returns the OHNativeWindow with the
 * surface lifecycle lock HELD so OnSurfaceDestroyed/OnSurfaceChanged cannot
 * run mid-frame. MUST be paired with SDL_OHOS_ReleaseNativeWindow() on every
 * exit path. Returns NULL when the surface is not ready (no lock held). */
OHOS_EXPORT void *SDL_OHOS_AcquireNativeWindow(void) __attribute__((weak));

/* Release the lifecycle lock taken by SDL_OHOS_AcquireNativeWindow. */
OHOS_EXPORT void SDL_OHOS_ReleaseNativeWindow(void) __attribute__((weak));

/* Return the current surface size in pixels. Returns 1 when valid. */
OHOS_EXPORT int SDL_OHOS_GetSurfaceSize(int *width, int *height) __attribute__((weak));

/* Return the physical XComponent pixel size (ArkTS onAreaChange). Used to
 * scale touch coordinates into the game's logical window space. */
OHOS_EXPORT int SDL_OHOS_GetPhysicalSize(int *width, int *height) __attribute__((weak));

/* Return 1 while the OHOS AVPlayer is actively rendering video into
 * the XComponent surface (the SDL renderer must pause output so the video
 * is not covered by the engine framebuffer). */
OHOS_EXPORT int SDL_OHOS_IsVideoPlaying(void) __attribute__((weak));

/* Deliver an XComponent touch event. Called from the ACE UI thread.
 * Touch coordinates are physical pixels; the backend scales them into the
 * logical window space. */
OHOS_EXPORT void SDL_OHOS_OnTouchEvent(int touch_type, float x, float y) __attribute__((weak));

/* Deliver one finger of an XComponent touch event as an SDL FINGER event.
 * Multi-finger state is what lets the engine map a two-finger tap onto the
 * right mouse button (skip movie / back out of menus). finger_id is the
 * ArkTS TouchObject.id, x/y are physical pixels (normalised to 0..1 here). */
OHOS_EXPORT void SDL_OHOS_OnFingerEvent(int finger_id, int touch_type, float x, float y) __attribute__((weak));

/* Notify the SDL video driver that the surface size changed. */
OHOS_EXPORT void SDL_OHOS_OnSurfaceChanged(int width, int height) __attribute__((weak));

/* Deliver a mouse event. action: 0 = button down, 1 = button up,
 * 2 = wheel (x = vertical scroll delta, y = horizontal scroll delta).
 * button: 1 = left, 2 = middle, 3 = right. x/y are physical pixels. */
OHOS_EXPORT void SDL_OHOS_OnMouseEvent(int action, int button, int x, int y) __attribute__((weak));

/* Deliver a key event. down: 1 = pressed, 0 = released. keycode is the
 * OHOS KeyCode (ohos.multimodalInput.keyCode); the SDL backend maps it to
 * a scancode for the keys the game uses. */
OHOS_EXPORT void SDL_OHOS_OnKeyEvent(int down, int keycode) __attribute__((weak));

/* Request the ArkTS shell to switch the OS window between fullscreen and
 * windowed mode (HarmonyOS PC / 2-in-1 tablets - the game settings menu
 * maps its fullscreen/windowed buttons onto this). The request is stored
 * here and picked up by the shell's 100 ms poll (pollFullscreen /
 * ackFullscreen NAPI functions); fullscreen ? 1 : 0.
 * Implemented in SDL_ohosvideo.c (libkrkrsdl2.so) so the engine and its
 * SDL video driver resolve it within their own .so at link time. */
OHOS_EXPORT void SDL_OHOS_SetAppFullscreen(int fullscreen) __attribute__((weak));

/* Current applied fullscreen state: -1 = unknown (never switched yet),
 * 0 = windowed, 1 = fullscreen. Backs the engine's GetFullScreenMode. */
OHOS_EXPORT int SDL_OHOS_GetAppFullscreenState(void) __attribute__((weak));

/* Polled by the ArkTS shell (libentry.so): the pending fullscreen request,
 * or -1 when there is none. Implemented in SDL_ohosvideo.c. */
OHOS_EXPORT int SDL_OHOS_PollFullscreenRequest(void) __attribute__((weak));

/* Acknowledge the shell applied the requested fullscreen state (0 windowed /
 * 1 fullscreen): records the applied state and clears the pending request
 * with a CAS so a newer request written in between is not lost. */
OHOS_EXPORT void SDL_OHOS_AckFullscreen(int applied) __attribute__((weak));

/* OHOS desktop "resolution" switch: the engine's SetZoom forwards the
 * requested logical window size here (windowed mode only); the shell's
 * poll reads it with SDL_OHOS_PollWindowSizeRequest and resizes the OS
 * window. Implemented in SDL_ohosvideo.c (libkrkrsdl2.so). */
OHOS_EXPORT void SDL_OHOS_SetAppWindowSize(int w, int h) __attribute__((weak));

/* Polled by the ArkTS shell (libentry.so): consumes a pending window-size
 * request (one-shot exchange). Returns 1 and fills w/h when a request was
 * pending, 0 otherwise. */
OHOS_EXPORT int SDL_OHOS_PollWindowSizeRequest(int *w, int *h) __attribute__((weak));

/* Append one diagnostic line to <data dir>/diag_fullscreen.log (falls back
 * to the files dir). Used to trace the fullscreen switch chain (TJS ->
 * engine -> driver -> state atom -> napi -> ArkTS) and the actual
 * XComponent canvas size on HarmonyOS PC / tablets. One fopen/append/fclose
 * per line; callers throttle repeated values. */
OHOS_EXPORT void SDL_OHOS_DiagLog(const char *line) __attribute__((weak));

#ifdef __cplusplus
}
#endif

#endif /* SDL_OHOS_BRIDGE_H */
