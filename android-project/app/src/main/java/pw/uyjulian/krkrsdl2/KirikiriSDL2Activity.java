package pw.uyjulian.krkrsdl2;

import android.content.res.AssetFileDescriptor;
import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity; 

public class KirikiriSDL2Activity extends SDLActivity {
    private static final String TAG = "KirikiriSDL2";
    private static final String ASSET_PREFIX = "asset:///";

    private TextureView movieView;
    private MediaPlayer moviePlayer;
    private String pendingMoviePath;
    private boolean moviePrepared;
    private boolean playMovieWhenPrepared;
    private float movieVolume = 1.0f;

    private static native void nativeOnMovieFinished();
    private static native void nativeOnMovieError(String message);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mLayout == null) return;

        movieView = new TextureView(this);
        movieView.setOpaque(true);
        movieView.setVisibility(View.GONE);
        movieView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (pendingMoviePath != null) prepareMovieOnUiThread(pendingMoviePath, texture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releaseMovieOnUiThread(false);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        mLayout.addView(movieView, params);
    }

    /**
     * The game has a fixed 1920x1080 coordinate system. SDL normally promotes
     * resizable windows to FULL_USER orientation, which can recreate the
     * Surface in portrait when an Android device resumes. Keep both the Java
     * activity and SDL's later orientation request locked to landscape.
     */
    @Override
    public void setOrientationBis(int width, int height, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public void openMovie(final String path) {
        runOnUiThread(() -> {
            releaseMovieOnUiThread(false);
            pendingMoviePath = path;
            playMovieWhenPrepared = false;
            moviePrepared = false;
            if (movieView == null) {
                reportMovieError("movie TextureView is unavailable");
                return;
            }
            movieView.setVisibility(View.VISIBLE);
            movieView.setKeepScreenOn(true);
            if (movieView.isAvailable()) {
                prepareMovieOnUiThread(path, movieView.getSurfaceTexture());
            }
        });
    }

    private void prepareMovieOnUiThread(String path, SurfaceTexture texture) {
        if (texture == null || path == null || !path.equals(pendingMoviePath) || moviePlayer != null) return;

        MediaPlayer player = new MediaPlayer();
        moviePlayer = player;
        Surface surface = new Surface(texture);
        AssetFileDescriptor asset = null;
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
            player.setSurface(surface);
            player.setVolume(movieVolume, movieVolume);
            player.setOnPreparedListener(preparedPlayer -> {
                if (preparedPlayer != moviePlayer) return;
                moviePrepared = true;
                if (playMovieWhenPrepared) preparedPlayer.start();
            });
            player.setOnCompletionListener(completedPlayer -> {
                if (completedPlayer != moviePlayer) return;
                releaseMovieOnUiThread(true);
                nativeOnMovieFinished();
            });
            player.setOnErrorListener((failedPlayer, what, extra) -> {
                if (failedPlayer == moviePlayer) {
                    reportMovieError("MediaPlayer error what=" + what + ", extra=" + extra);
                }
                return true;
            });

            if (path.startsWith(ASSET_PREFIX)) {
                String assetPath = path.substring(ASSET_PREFIX.length());
                asset = getAssets().openFd(assetPath);
                player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            } else {
                player.setDataSource(path);
            }
            player.prepareAsync();
        } catch (Exception error) {
            reportMovieError(error.toString());
        } finally {
            surface.release();
            if (asset != null) {
                try {
                    asset.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public void playMovie() {
        runOnUiThread(() -> {
            playMovieWhenPrepared = true;
            if (moviePlayer != null && moviePrepared) moviePlayer.start();
        });
    }

    public void pauseMovie() {
        runOnUiThread(() -> {
            playMovieWhenPrepared = false;
            if (moviePlayer != null && moviePrepared && moviePlayer.isPlaying()) moviePlayer.pause();
        });
    }

    public void rewindMovie() {
        runOnUiThread(() -> {
            if (moviePlayer != null && moviePrepared) moviePlayer.seekTo(0);
        });
    }

    public void stopMovie() {
        runOnUiThread(() -> releaseMovieOnUiThread(false));
    }

    public void setMovieVolume(final float volume) {
        movieVolume = Math.max(0.0f, Math.min(1.0f, volume));
        runOnUiThread(() -> {
            if (moviePlayer != null) moviePlayer.setVolume(movieVolume, movieVolume);
        });
    }

    private void reportMovieError(String message) {
        Log.e(TAG, "Movie playback failed: " + message);
        releaseMovieOnUiThread(true);
        nativeOnMovieError(message);
    }

    private void releaseMovieOnUiThread(boolean keepCurrentSurface) {
        pendingMoviePath = null;
        playMovieWhenPrepared = false;
        moviePrepared = false;
        if (moviePlayer != null) {
            moviePlayer.setOnPreparedListener(null);
            moviePlayer.setOnCompletionListener(null);
            moviePlayer.setOnErrorListener(null);
            moviePlayer.reset();
            moviePlayer.release();
            moviePlayer = null;
        }
        if (!keepCurrentSurface && movieView != null) {
            movieView.setKeepScreenOn(false);
            movieView.setVisibility(View.GONE);
        } else if (movieView != null) {
            movieView.setKeepScreenOn(false);
            movieView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        releaseMovieOnUiThread(false);
        super.onDestroy();
    }
}
