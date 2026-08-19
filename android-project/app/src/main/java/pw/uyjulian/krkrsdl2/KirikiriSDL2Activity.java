package pw.uyjulian.krkrsdl2;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException; 

public class KirikiriSDL2Activity extends SDLActivity {
    private static final String TAG = "KirikiriSDL2";
    private static final String ASSET_PREFIX = "asset:///";

    private static final int STORAGE_PERMISSION_REQUEST = 9001;
    private static final String SAVE_SUBDIR = "YosugaSoraHD" + File.separator + "savedata";
    private static final String NO_MEDIA = ".nomedia";
    // Cached public save directory absolute path (UTF-8), shared with native.
    private static String sPublicSaveDir = null;

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

        // Ask for the storage permission needed to write to public Downloads.
        // On Android 11+ that means MANAGE_EXTERNAL_STORAGE (opens system
        // settings); on older versions the WRITE/READ pair is requested.
        requestStoragePermissionIfNeeded();

        // Build the public save directory (and .nomedia marker) so saves live
        // in a user-reachable folder.  Old saves are NOT auto-migrated here;
        // a fresh install simply starts using the new location.
        getPublicSaveDataPath();
    }

    // ---- Storage permission + public save directory -----------------------
    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: scoped storage requires MANAGE_EXTERNAL_STORAGE,
            // which can only be granted from system Settings.
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored2) {
                        // No settings screen available; stay in the private dir.
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                }, STORAGE_PERMISSION_REQUEST);
            }
        }
    }

    private boolean hasPublicStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // granted at install time on <= API 22
    }

    /**
     * Returns the public Downloads save folder path, creating it (plus a
     * .nomedia marker so the media scanner never publishes the save thumbnails
     * into the system gallery) if needed.  Returns null when public access is
     * unavailable.  Called from native through JNI; keep it public and
     * side-effect safe.
     */
    public String getPublicSaveDataPath() {
        if (sPublicSaveDir != null) return sPublicSaveDir;
        if (!hasPublicStorageAccess()) return null;

        // Prefer the real public Downloads folder on Android 11+ (where
        // MANAGE_EXTERNAL_STORAGE makes it writable).  On Android 10
        // scoped storage blocks direct file writes to Downloads even with
        // WRITE_EXTERNAL_STORAGE under targetSdk 37, so fall back to the
        // app-external folder which is still user-reachable (via the
        // Files app / USB) and persists across un-installs awaiting backup.
        File saveDir = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File publicDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (publicDir != null)
                saveDir = new File(publicDir, SAVE_SUBDIR);
        } else {
            File ext = getExternalFilesDir(null);
            if (ext != null)
                saveDir = new File(ext, "DownloadSavedata");
        }
        if (saveDir == null) return null;

        try {
            if (!saveDir.exists() && !saveDir.mkdirs()) return null;
            File noMedia = new File(saveDir, NO_MEDIA);
            if (!noMedia.exists()) {
                if (!noMedia.createNewFile())
                    Log.w(TAG, "Could not create " + NO_MEDIA);
            }
            sPublicSaveDir = saveDir.getAbsolutePath();
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Failed to prepare public save directory", e);
            return null;
        }
        return sPublicSaveDir;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Re-run public dir setup now that permission is granted.
            getPublicSaveDataPath();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the user returns from the system Settings screen (where they
        // granted MANAGE_EXTERNAL_STORAGE on Android 11+), try to set up the
        // public save directory now.  The native helper re-queries this
        // method, so an in-session grant takes effect without a restart.
        if (sPublicSaveDir == null && hasPublicStorageAccess())
            getPublicSaveDataPath();
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
