package com.shuimo0413.yosuganosora.hdremake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Android bootstrap: detects the external game data (Download/YosugaSoraHD
 * or Android/data/<pkg>), offers download / import, extracts zips and
 * data.xp3 archives, then starts the engine activity. Mirrors the OHOS
 * shell behaviour (including the one-time re-import prompt after an app
 * update).
 */
public class BootstrapActivity extends Activity {
    private static final int DESIGN_WIDTH = 1920;
    private static final int DESIGN_HEIGHT = 1080;
    private static final int PROXY_DIRECT = 1;
    private static final int PROXY_GH = 2;
    private static final int PROXY_CRAFT = 3;
    // Download accelerator nodes: (display name, proxy prefix). The
    // prefix is prepended to the full GitHub URL. Empty prefix = direct.
    // This is the BUILT-IN FALLBACK list: the app refreshes the live list
    // from accelerator-nodes.json in the repo root when it can reach GitHub
    // raw, so nodes can be added/removed without shipping a new APK.
    private static final String[][] DEFAULT_NODES = {
        {"GitHub 直链", ""},
        {"GH-PROXY.CN", "https://gh-proxy.cn/"},
        {"GH-PROXY.ORG", "https://gh-proxy.org/"},
        {"CDN.GH-PROXY.ORG", "https://cdn.gh-proxy.org/"},
        {"AXISNOW.GH-PROXY.ORG", "https://axisnow.gh-proxy.org/"},
        {"V6.GH-PROXY.ORG", "https://v6.gh-proxy.org/"}
    };
    // Live node list; starts as the built-in fallback and is replaced by the
    // fetched accelerator-nodes.json when available.
    private static volatile String[][] ACCEL_NODES = DEFAULT_NODES;
    // Node latency cache (ms); -1 = unknown/failed. Index matches ACCEL_NODES.
    private static volatile long[] NODE_LATENCY = new long[DEFAULT_NODES.length];
    private static final int ACTION_NONE = 0;
    private static final int ACTION_DOWNLOAD = 1;
    private static final int ACTION_IMPORT = 2;
    private static final String FALLBACK_BASE_URL =
            "https://github.com/WarSkyGod/yosuga-no-sora-remake/releases/download/v1.0.6/";

    /** Keeps the bootstrap artwork and its hit regions in one fixed canvas. */
    private static final class FixedAspectLayout extends FrameLayout {
        FixedAspectLayout(android.content.Context context) {
            super(context);
            setClipChildren(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? DESIGN_WIDTH : MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? DESIGN_HEIGHT : MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            if (getChildCount() > 0) {
                View child = getChildAt(0);
                child.measure(MeasureSpec.makeMeasureSpec(DESIGN_WIDTH, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(DESIGN_HEIGHT, MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (getChildCount() == 0) return;
            View child = getChildAt(0);
            child.layout(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
            float scale = Math.min(getWidth() / (float) DESIGN_WIDTH,
                    getHeight() / (float) DESIGN_HEIGHT);
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setTranslationX((getWidth() - DESIGN_WIDTH * scale) / 2f);
            child.setTranslationY((getHeight() - DESIGN_HEIGHT * scale) / 2f);
        }
    }

    private static final String TAG = "YosugaBootstrap";
    private static final String PREFS = "data_setup";
    private static final String KEY_BASE_URL = "custom_base_url";
    private static final String KEY_PROXY_PREFIX = "custom_proxy_prefix";
    private static final String KEY_CONFIRMED_VERSION = "confirmed_version";
    /** Poison pill telling the extraction thread the download loop is done. */
    private static final Object EXTRACT_DONE = new Object();
    // Injected at build time (gradle property defaultBaseUrl, set by CI from
    // the publishing repository). Local builds leave it empty: the download
    // field then requires the user to type the data-assets.json location.
    private static final String DEFAULT_BASE_URL = BuildConfig.DEFAULT_BASE_URL;

    private FixedAspectLayout root;
    private TextView messageView;
    private TextView progressView;
    private View progressFillView;
    private ImageView progressTrackView;
    // Second (extract) progress bar: shown while an archive decompression
    // runs (its own thread in the download pipeline), hidden when done.
    private TextView extractTextView;
    private View extractFillView;
    private ImageView extractTrackView;
    private ImageView directLabelView;
    private ImageView ghProxyLabelView;
    private ImageView craftProxyLabelView;
    private ImageView downloadLabelView;
    private ImageView importLabelView;
    private EditText baseUrlInput;
    private EditText proxyInput;
    private Button directButton;
    private Button ghProxyButton;
    private Button craftProxyButton;
    private Button downloadButton;
    private Button importButton;
    // Static + volatile on purpose: the transfer threads outlive an Activity
    // recreation (backgrounding the app can destroy and rebuild it). An
    // instance flag would reset to false on recreation, so the re-shown
    // action buttons would let a second tap start a PARALLEL download that
    // fights the still-running first one over the same files and the UI.
    private static volatile boolean busy = false;
    private int selectedProxy = PROXY_DIRECT;
    private static volatile int activeAction = ACTION_NONE;
    // Pointer-hover highlight (mouse / trackpad): mirrors pressed state so
    // hovering a button shows its active artwork without pressing.
    private int hoverAction = ACTION_NONE;
    private int hoverProxy = ACTION_NONE;

    /** The currently alive activity instance. Transfer threads keep running
     *  across recreations; routing their UI callbacks through this reference
     *  keeps the progress bar updating on the NEW instance. */
    private static volatile BootstrapActivity sCurrent;

    private void runOnUi(Runnable r) {
        BootstrapActivity a = sCurrent;
        if (a != null) {
            // MUST be runOnUiThread: it was mass-renamed to runOnUi in one
            // sed pass, which made runOnUi call itself and blow the stack
            // (StackOverflowError) the first time any message was shown.
            a.runOnUiThread(r);
        }
    }

    private static final int STORAGE_PERMISSION_REQUEST = 9001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // Build the content view FIRST: the immersive setup below touches
        // the window insets controller, which NPEs on some Android 16
        // environments (卓易通) when the DecorView does not exist yet.
        buildUi();
        applyImmersive();
        requestStoragePermissionIfNeeded();
        sCurrent = this;
        if (busy) {
            // A transfer survived this recreation: re-attach the progress UI
            // (setBusy is idempotent and rebinds every view to this instance)
            // instead of probing back to the setup page.
            setBusy(true);
        } else {
            probeData();
        }
    }

    @Override
    protected void onDestroy() {
        if (sCurrent == this) {
            sCurrent = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        // Returning from the system Settings screen (MANAGE_EXTERNAL_STORAGE
        // on Android 11+) may have just granted public storage: re-probe so
        // a ready data tree starts the game directly. While a transfer is
        // running, re-attach the progress UI instead - probing would reset
        // the page and invite a parallel second download.
        if (busy) {
            setBusy(true);
        } else {
            probeData();
        }
    }

    /** Storage permission for the public Downloads write (mirrors the engine
     * activity): WRITE/READ pair on Android 10-, MANAGE_EXTERNAL_STORAGE via
     * system Settings on Android 11+. chooseDataParent() probes the actual
     * writability and falls back to Android/data/<pkg> when it is missing. */
    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
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

    /** Fullscreen immersive: hide the status/navigation bars (including the
     * gesture pill area) so the bootstrap page has no bottom black strip. */
    private void applyImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller =
                        getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController
                                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable t) {
            // Immersion is cosmetic: never crash the bootstrap over it.
        }
    }

    // ---- UI -----------------------------------------------------------------
    private void buildUi() {
        root = new FixedAspectLayout(this);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout canvas = new FrameLayout(this);
        root.addView(canvas, new FrameLayout.LayoutParams(DESIGN_WIDTH, DESIGN_HEIGHT));

        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.background);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        canvas.addView(background, frame(DESIGN_WIDTH, DESIGN_HEIGHT, 0, 0));

        // These are the visible controls from the supplied 1920x1080
        // artwork. They are separate from the transparent hit targets below
        // so the labels do not depend on the Android font, density, or
        // widget theme.
        directLabelView = makeAssetImage(R.drawable.github_direct);
        ghProxyLabelView = makeAssetImage(R.drawable.gh_proxy_label);
        craftProxyLabelView = makeAssetImage(R.drawable.craft_hello_label);
        downloadLabelView = makeAssetImage(R.drawable.download_label);
        importLabelView = makeAssetImage(R.drawable.import_label);
        progressTrackView = makeAssetImage(R.drawable.progress_track);
        progressTrackView.setVisibility(View.GONE);
        canvas.addView(directLabelView, frame(356, 123, 200, 430));
        canvas.addView(ghProxyLabelView, frame(338, 105, 600, 440));
        canvas.addView(craftProxyLabelView, frame(673, 105, 1000, 440));
        canvas.addView(progressTrackView, frame(1215, 26, 210, 690));
        canvas.addView(downloadLabelView, frame(136, 57, 1270, 800));
        canvas.addView(importLabelView, frame(201, 57, 1470, 800));

        progressFillView = new View(this);
        GradientDrawable progressFill = new GradientDrawable();
        progressFill.setColor(Color.rgb(23, 131, 255));
        progressFill.setCornerRadius(9f);
        progressFillView.setBackground(progressFill);
        progressFillView.setVisibility(View.GONE);
        canvas.addView(progressFillView, frame(0, 18, 214, 694));

        // Extract progress bar (green), stacked directly above the download
        // bar: text at y=622..666, track at y=668..686 (download track sits
        // at y=690..716). Only visible while a decompression is running.
        extractTrackView = makeAssetImage(R.drawable.progress_track);
        extractTrackView.setVisibility(View.GONE);
        canvas.addView(extractTrackView, frame(1215, 18, 210, 668));
        extractFillView = new View(this);
        GradientDrawable extractFill = new GradientDrawable();
        extractFill.setColor(Color.rgb(76, 175, 80));
        extractFill.setCornerRadius(7f);
        extractFillView.setBackground(extractFill);
        extractFillView.setVisibility(View.GONE);
        canvas.addView(extractFillView, frame(0, 10, 214, 672));
        extractTextView = new TextView(this);
        extractTextView.setText("");
        extractTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        extractTextView.setTextColor(Color.rgb(200, 240, 200));
        extractTextView.setGravity(android.view.Gravity.CENTER);
        extractTextView.setBackgroundColor(Color.TRANSPARENT);
        extractTextView.setVisibility(View.GONE);
        canvas.addView(extractTextView, frame(1215, 44, 210, 622));

        // These fields are kept unattached so the downloader retains its
        // custom URL/proxy behaviour. They are exposed by long-pressing the
        // local-download entry; the normal screen stays identical to the
        // supplied 1920x1080 artwork.
        baseUrlInput = new EditText(this);
        baseUrlInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_BASE_URL, ""));
        baseUrlInput.setTextSize(12f);
        baseUrlInput.setSingleLine(true);
        baseUrlInput.setHint("下载地址（留空使用构建内置的发布仓库；填直链或镜像地址则直接使用，不再套加速前缀）");
        proxyInput = new EditText(this);
        proxyInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PROXY_PREFIX, ""));
        proxyInput.setTextSize(12f);
        proxyInput.setSingleLine(true);
        proxyInput.setHint("加速代理前缀（用于给上游下载地址自动生成加速链接；留空=直连）");

        messageView = new TextView(this);
        messageView.setText(" ");
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        messageView.setTextColor(Color.RED);
        messageView.setGravity(android.view.Gravity.CENTER);

        progressView = new TextView(this);
        progressView.setText("");
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        progressView.setTextColor(Color.WHITE);
        progressView.setGravity(android.view.Gravity.CENTER);
        progressView.setBackgroundColor(Color.TRANSPARENT);
        progressView.setVisibility(View.GONE);

        canvas.addView(messageView, frame(1520, 100, 200, 580));
        canvas.addView(progressView, frame(1450, 100, 235, 710));

        // The left artwork entry and the lower-right action both start the
        // same download. The old layout accidentally placed the only hit
        // target over the left entry, leaving "开始下载" inert.
        Button localDownloadButton = makeOverlayButton("本地文件下载");
        localDownloadButton.setOnClickListener(v -> startDownload());
        canvas.addView(localDownloadButton, frame(380, 90, 210, 325));

        downloadButton = makeOverlayButton("开始下载");
        downloadButton.setOnClickListener(v -> startDownload());
        importButton = makeOverlayButton("导入本地文件");
        importButton.setOnClickListener(v -> startImport());
        attachActionFeedback(downloadButton, downloadLabelView,
                R.drawable.download_label, R.drawable.download_label_active, ACTION_DOWNLOAD);
        attachActionFeedback(importButton, importLabelView,
                R.drawable.import_label, R.drawable.import_label_active, ACTION_IMPORT);
        canvas.addView(downloadButton, frame(300, 135, 1240, 755));
        canvas.addView(importButton, frame(430, 125, 1450, 755));

        directButton = makeOverlayButton("GitHub直链");
        ghProxyButton = makeOverlayButton("GH-PROXY");
        craftProxyButton = makeOverlayButton("CRAFT-HELLO PROXY");
        attachProxyFeedback(directButton, PROXY_DIRECT);
        attachProxyFeedback(ghProxyButton, PROXY_GH);
        attachProxyFeedback(craftProxyButton, PROXY_CRAFT);
        canvas.addView(directButton, frame(550, 145, 20, 425));
        canvas.addView(ghProxyButton, frame(420, 145, 570, 425));
        canvas.addView(craftProxyButton, frame(740, 145, 980, 425));

        updateProxyArtwork();

        setContentView(root);
        refreshAcceleratorNodes();
    }

    private FrameLayout.LayoutParams frame(int width, int height, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private ImageView makeAssetImage(int resourceId) {
        ImageView image = new ImageView(this);
        image.setImageResource(resourceId);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setClickable(false);
        image.setFocusable(false);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private Button makeOverlayButton(String description) {
        Button button = new Button(this);
        button.setText("");
        button.setContentDescription(description);
        button.setBackground(new ColorDrawable(Color.TRANSPARENT));
        button.setTextColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setAllCaps(false);
        return button;
    }

    private void attachProxyFeedback(Button button, int proxy) {
        button.setOnClickListener(view -> toggleProxy(proxy));
        button.setOnHoverListener((view, event) -> {
            if (view.isEnabled() && event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                hoverProxy = proxy;
                updateProxyArtwork();
            } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                if (hoverProxy == proxy) hoverProxy = ACTION_NONE;
                updateProxyArtwork();
            }
            return false;
        });
    }

    private void toggleProxy(int proxy) {
        // One source must always be selected. Tapping the active item keeps
        // it active; tapping another item switches the selection atomically.
        selectedProxy = proxy;
        if (selectedProxy == PROXY_GH) {
            proxyInput.setText("https://gh-proxy.cn/");
            updateProxyArtwork();
        } else if (selectedProxy == PROXY_CRAFT) {
            proxyInput.setText("https://proxy.craft-hello.top/proxy/");
            updateProxyArtwork();
        } else {
            proxyInput.setText("");
            updateProxyArtwork();
        }
    }

    private void updateProxyArtwork() {
        directLabelView.setImageResource(
                selectedProxy == PROXY_DIRECT || hoverProxy == PROXY_DIRECT
                ? R.drawable.github_direct_selected : R.drawable.github_direct);
        ghProxyLabelView.setImageResource(
                selectedProxy == PROXY_GH || hoverProxy == PROXY_GH
                ? R.drawable.gh_proxy_label_selected : R.drawable.gh_proxy_label);
        craftProxyLabelView.setImageResource(
                selectedProxy == PROXY_CRAFT || hoverProxy == PROXY_CRAFT
                ? R.drawable.craft_hello_label_selected : R.drawable.craft_hello_label);
        directButton.setSelected(selectedProxy == PROXY_DIRECT);
        ghProxyButton.setSelected(selectedProxy == PROXY_GH);
        craftProxyButton.setSelected(selectedProxy == PROXY_CRAFT);
    }

    private void attachActionFeedback(Button button, ImageView artwork,
            int normalResource, int activeResource, int action) {
        button.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                artwork.setImageResource(activeResource);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                updateActionArtwork();
            }
            return false;
        });
        // Mouse / trackpad hover: light the button up while the pointer is
        // over it, restore the normal artwork when it leaves.
        button.setOnHoverListener((view, event) -> {
            if (view.isEnabled() && event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                hoverAction = action;
                artwork.setImageResource(activeResource);
            } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                if (hoverAction == action) hoverAction = ACTION_NONE;
                updateActionArtwork();
            }
            return false;
        });
    }

    private void updateActionArtwork() {
        downloadLabelView.setImageResource(
                (busy && activeAction == ACTION_DOWNLOAD) || hoverAction == ACTION_DOWNLOAD
                ? R.drawable.download_label_active : R.drawable.download_label);
        importLabelView.setImageResource(
                (busy && activeAction == ACTION_IMPORT) || hoverAction == ACTION_IMPORT
                ? R.drawable.import_label_active : R.drawable.import_label);
    }

    private void setProgress(String text, int percent) {
        runOnUi(() -> {
            progressView.setText(text);
            int clamped = Math.max(0, Math.min(100, percent));
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) progressFillView.getLayoutParams();
            params.width = Math.round(1207f * clamped / 100f);
            progressFillView.setLayoutParams(params);
        });
    }

    /** Progress of the running decompression (own bar, shown until the
     *  extraction finishes and hideExtractProgress() is called). */
    private void setExtractProgress(String text, int percent) {
        runOnUi(() -> {
            extractTextView.setText(text);
            extractTextView.setVisibility(View.VISIBLE);
            extractTrackView.setVisibility(View.VISIBLE);
            extractFillView.setVisibility(View.VISIBLE);
            int clamped = Math.max(0, Math.min(100, percent));
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) extractFillView.getLayoutParams();
            params.width = Math.round(1207f * clamped / 100f);
            extractFillView.setLayoutParams(params);
        });
    }

    private void hideExtractProgress() {
        runOnUi(() -> {
            extractTextView.setVisibility(View.GONE);
            extractTrackView.setVisibility(View.GONE);
            extractFillView.setVisibility(View.GONE);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) extractFillView.getLayoutParams();
            params.width = 0;
            extractFillView.setLayoutParams(params);
        });
    }

    private void setMessage(String text) {
        runOnUi(() -> messageView.setText(text));
    }

    private void setBusy(boolean value) {
        busy = value;
        if (!value) activeAction = ACTION_NONE;
        runOnUi(() -> {
            downloadButton.setEnabled(!value);
            importButton.setEnabled(!value);
            directButton.setEnabled(!value);
            ghProxyButton.setEnabled(!value);
            craftProxyButton.setEnabled(!value);
            updateProxyArtwork();
            updateActionArtwork();
            progressView.setVisibility(value ? View.VISIBLE : View.GONE);
            progressTrackView.setVisibility(value ? View.VISIBLE : View.GONE);
            progressFillView.setVisibility(value ? View.VISIBLE : View.GONE);
            if (!value) {
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) progressFillView.getLayoutParams();
                params.width = 0;
                progressFillView.setLayoutParams(params);
                // The transfer is over: make sure the extract bar is gone.
                hideExtractProgress();
            }
            // Keep the screen ON while downloading / extracting so the
            // device does not go to sleep mid-transfer.
            if (value) {
                getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    // ---- data location ------------------------------------------------------
    private File downloadRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "YosugaSoraHD");
    }

    private File appExternalRoot() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext.getParentFile() : null; // Android/data/<pkg>
    }

    private boolean dataReady(File dataDir) {
        if (dataDir == null) return false;
        return new File(dataDir, "startup.tjs").isFile()
                && new File(dataDir, "system").isDirectory();
    }

    /** First location that contains a ready data/ tree. */
    private File findReadyDataDir() {
        File d = new File(downloadRoot(), "data");
        if (dataReady(d)) return d;
        File p = appExternalRoot();
        if (p != null) {
            File d2 = new File(p, "data");
            if (dataReady(d2)) return d2;
        }
        return null;
    }

    /**
     * Pick the writable data parent: the public Download/YosugaSoraHD folder
     * when the platform allows writing there, otherwise Android/data/<pkg>.
     */
    private File chooseDataParent() {
        File dl = downloadRoot();
        try {
            if (!dl.exists() && !dl.mkdirs()) throw new IOException("mkdirs failed");
            File probe = new File(dl, ".probe");
            if (!probe.createNewFile() && !probe.exists()) throw new IOException("probe failed");
            probe.delete();
            return dl;
        } catch (Exception e) {
            Log.w(TAG, "public Download not writable; using app-external dir", e);
            File p = appExternalRoot();
            return p; // may be null; caller falls back to filesDir
        }
    }

    // ---- probe / confirm ----------------------------------------------------
    private void probeData() {
        File ready = findReadyDataDir();
        if (ready == null) {
            // Bundled install (old APK with assets): let the engine read assets.
            showSetup("");
            return;
        }
        File parent = chooseDataParent();
        if (parent != null) {
            ensureNoMedia(parent);
        }
        maybeConfirmUpdate(ready);
    }

    private void ensureNoMedia(File parent) {
        File marker = new File(parent, ".nomedia");
        if (!marker.exists()) {
            try { marker.createNewFile(); } catch (IOException ignored) {}
        }
    }

    /** After an app update, ask ONCE whether to re-import the game package. */
    private void maybeConfirmUpdate(File readyDir) {
        try {
            int version = getVersionCode();
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.getInt(KEY_CONFIRMED_VERSION, -1) == version) {
                startEngine(readyDir);
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("检测到已有游戏数据")
                .setMessage("是否重新导入游戏包？\n“重新导入”将删除现有数据并跳转到导入页面；“使用旧数据”将直接启动。")
                .setPositiveButton("重新导入", (d, w) -> {
                    prefs.edit().putInt(KEY_CONFIRMED_VERSION, version).apply();
                    dropOldData();
                    showSetup("请重新导入游戏包");
                })
                .setNegativeButton("使用旧数据", (d, w) -> {
                    prefs.edit().putInt(KEY_CONFIRMED_VERSION, version).apply();
                    startEngine(readyDir);
                })
                .setCancelable(false)
                .show();
        } catch (Exception e) {
            startEngine(readyDir);
        }
    }

    private int getVersionCode() throws PackageManager.NameNotFoundException {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return (int) info.getLongVersionCode();
    }

    private void dropOldData() {
        for (File dir : new File[]{new File(downloadRoot(), "data"),
                appExternalRoot() != null ? new File(appExternalRoot(), "data") : null}) {
            if (dir != null) deleteTree(dir);
        }
    }

    private void showSetup(String message) {
        setMessage(message);
        setProgress("", 0);
    }

    // ---- engine handoff -----------------------------------------------------
    private void startEngine(File dataDir) {
        setProgress("正在启动游戏…", 100);
        Intent intent = new Intent(this, KirikiriSDL2Activity.class);
        intent.putExtra("dataDir", dataDir != null ? dataDir.getAbsolutePath() : "");
        startActivity(intent);
        finish();
    }

    // ---- download -----------------------------------------------------------
    private void startDownload() {
        if (busy) return;
        activeAction = ACTION_DOWNLOAD;
        setBusy(true);
        setMessage("");
        setProgress("正在获取下载清单…", 0);
        new Thread(() -> {
            try {
                List<String[]> assets = loadManifest();
                if (assets.isEmpty()) {
                    fail("无法读取下载清单（data-assets.json），请检查网络后重试");
                    return;
                }
                File parent = chooseDataParent();
                if (parent == null) {
                    fail("无法使用外部存储，无法下载数据");
                    return;
                }
                ensureNoMedia(parent);
                DataExtractService.start(this);
                try {
                    // Extract the zips STRAIGHT into the data directory (no
                    // staging dir, no cross-volume move).
                    File dataDir = new File(parent, "data");
                    if (dataDir.exists()) deleteTree(dataDir);
                    long total = 0;
                    for (String[] a : assets) total += Long.parseLong(a[2]);
                    long done = 0;
                    long startTime = System.currentTimeMillis();
                    // Extraction pipeline: a dedicated thread pulls finished
                    // archives off the queue while the download loop keeps
                    // fetching the next one, so decompression no longer
                    // stalls the transfer. The queue is tiny on purpose -
                    // the disk then holds at most ~3 multi-GB zips.
                    final java.util.concurrent.BlockingQueue<Object> extractQueue =
                            new java.util.concurrent.ArrayBlockingQueue<>(2);
                    final java.util.concurrent.atomic.AtomicReference<Exception>
                            extractError = new java.util.concurrent.atomic.AtomicReference<>(null);
                    final File extractDir = dataDir;
                    final int packCount = assets.size();
                    Thread extractor = new Thread(() -> {
                        int seq = 0;
                        while (true) {
                            Object item;
                            try {
                                item = extractQueue.take();
                            } catch (InterruptedException ie) {
                                extractError.compareAndSet(null,
                                        new IOException("解压线程被中断"));
                                return;
                            }
                            if (item == EXTRACT_DONE) return;
                            // Once extraction has failed, just drain the
                            // queue so the producer never blocks forever.
                            if (extractError.get() != null) continue;
                            File zip = (File) item;
                            seq++;
                            try {
                                extractZipTo(zip, extractDir,
                                        "解压 " + zip.getName()
                                                + "（" + seq + "/" + packCount + "）");
                                zip.delete();
                            } catch (Exception e) {
                                extractError.compareAndSet(null, e);
                            }
                        }
                    });
                    extractor.start();
                    int index = 0;
                    try {
                        for (String[] asset : assets) {
                            index++;
                            // Stop pulling new archives once extraction has
                            // failed; the wrap-up below reports the error.
                            if (extractError.get() != null) break;
                            String name = asset[0];
                            String sha = asset[1];
                            long size = Long.parseLong(asset[2]);
                            File zip = new File(getCacheDir(), name);
                            downloadFile(asset[3], zip, size, done, total, name, startTime);
                            verifySha(zip, sha);
                            extractQueue.put(zip);
                            done += size;
                        }
                    } finally {
                        try {
                            extractQueue.put(EXTRACT_DONE);
                        } catch (InterruptedException ie) {
                            extractor.interrupt();
                        }
                        try {
                            extractor.join();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    Exception ee = extractError.get();
                    if (ee != null) {
                        throw new IOException(
                                ee.getMessage() == null ? "解压失败" : ee.getMessage(), ee);
                    }
                    installIntoDataDir(dataDir, parent);
                } finally {
                    DataExtractService.stop(this);
                }
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                fail("下载失败：" + e.getMessage());
            } finally {
                setBusy(false);
            }
        }).start();
    }

    /** The download root honoring the custom URL input; always ends with
     * "/". Shared by the download flow and the import completeness check. */
    private static volatile String sLatestUpstreamBase = null;
    private static volatile long sLatestUpstreamAt = 0;

    /** Follows the upstream repo's newest release that actually carries data
     *  assets, instead of pinning a version: the maintainer rebuilds data via
     *  Actions into every new release tag, so the download root must track
     *  the tag. Cached for 10 min (like the node list). On any failure the
     *  caller falls back to FALLBACK_BASE_URL. */
    private String resolveUpstreamLatestBase() {
        long now = System.currentTimeMillis();
        if (sLatestUpstreamBase != null && now - sLatestUpstreamAt < 10L * 60 * 1000) {
            return sLatestUpstreamBase;
        }
        try {
            // Query the upstream releases via BOTH the direct API and the
            // gh-proxy.cn mirror (Fujian-reachable): the manifest fetch is
            // already accelerated, but this version lookup used to run
            // unaccelerated and could stall ~16s on a blocked api.github.com.
            String apiDirect = "https://api.github.com/repos/WarSkyGod/yosuga-no-sora-remake/releases?per_page=30";
            String apiMirror = "https://gh-proxy.cn/" + apiDirect;
            String jsonText = null;
            for (String candidate : new String[]{apiDirect, apiMirror}) {
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL(candidate).openConnection(systemProxy());
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } finally {
                        c.disconnect();
                    }
                    jsonText = sb.toString();
                    break;
                } catch (Exception ignored) {
                    // try next candidate
                }
            }
            if (jsonText == null) return null;
            JSONArray releases = new JSONArray(jsonText);
            String tag = null;
            for (int i = 0; i < releases.length() && tag == null; i++) {
                JSONObject rel = releases.optJSONObject(i);
                if (rel == null) continue;
                JSONArray assets = rel.optJSONArray("assets");
                boolean hasManifest = false;
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject a = assets.optJSONObject(j);
                        if (a != null && "data-assets.json".equals(a.optString("name"))) {
                            hasManifest = true;
                            break;
                        }
                    }
                }
                if (hasManifest) tag = rel.optString("tag_name");
            }
            if (tag == null || tag.isEmpty()) return null;
            sLatestUpstreamBase = "https://github.com/WarSkyGod/yosuga-no-sora-remake/releases/download/"
                    + tag + "/";
            sLatestUpstreamAt = now;
            return sLatestUpstreamBase;
        } catch (Exception e) {
            // Cache the failure too: when api.github.com is unreachable
            // (no proxy / GFW), retrying on every loadManifest() would add
            // up to ~16s of dead timeout per call. Remember the attempt so
            // the next 10 minutes go straight to FALLBACK_BASE_URL.
            sLatestUpstreamAt = now;
            return null;
        }
    }

    private String resolveBaseUrl() {
        String base = baseUrlInput.getText().toString().trim();
        if (base.isEmpty()) {
            base = DEFAULT_BASE_URL;
            if (base.isEmpty()) {
                String latest = resolveUpstreamLatestBase();
                base = latest != null ? latest : FALLBACK_BASE_URL;
            }
        }
        if (!base.endsWith("/")) base += "/";
        return base;
    }

    /** Returns [name, sha256, size, url] tuples. The accelerator proxy
     * prefix (when set) is prepended to every asset URL, mirroring the
     * OHOS downloader. */
    private List<String[]> loadManifest() throws Exception {
        List<String[]> out = new ArrayList<>();
        String base = resolveBaseUrl();
        final String proxy = proxyInput.getText().toString().trim();
        // A custom base URL is the final source (direct or mirror): never
        // stack the accelerator prefix on top of it.
        boolean customBase = !baseUrlInput.getText().toString().trim().isEmpty();
        String manifestUrl = (customBase || proxy.isEmpty())
                ? (base + "data-assets.json")
                : (proxy + base + "data-assets.json");
        HttpURLConnection conn = (HttpURLConnection) new URL(manifestUrl)
                .openConnection(systemProxy());
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            conn.disconnect();
        }
        JSONObject rootObj = new JSONObject(sb.toString());
        JSONArray assets = rootObj.optJSONArray("assets");
        if (assets == null) return out;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String assetUrl = (customBase || proxy.isEmpty())
                    ? (base + a.getString("name"))
                    : (proxy + base + a.getString("name"));
            out.add(new String[]{
                a.getString("name"),
                a.optString("sha256", ""),
                String.valueOf(a.optLong("size", 0)),
                assetUrl,
            });
        }
        return out;
    }

    private void downloadFile(String urlStr, File dest, long size,
            long doneBase, long total, String label, long startTime) throws IOException {
        // Show download progress immediately: the "正在获取下载清单…" text set
        // by startDownload stays on screen until the FIRST chunk completes,
        // which with slow mirrors looks like the manifest fetch is hanging
        // while the OS network meter already shows heavy traffic.
        int startPct = total > 0 ? (int) (doneBase * 100 / total) : 0;
        setProgress(String.format(Locale.US,
                "正在下载 %s  %d%%  %s / %s",
                label, Math.min(99, startPct), fmtSize(doneBase), fmtSize(total)),
                Math.min(99, startPct));
        // 6 concurrent Range workers over 8MB chunks, same scheme as the
        // OHOS build: throughput comes from parallelism. Each worker writes
        // its chunk at the exact offset via FileChannel.positional write,
        // so retries stay resume-safe and SHA-256 guards the result.
        final int threads = 32;
        final long chunk = 8L * 1024 * 1024;
        final long nChunks = (size + chunk - 1) / chunk;
        final AtomicLong doneSum = new AtomicLong(0);
        final AtomicReference<IOException> failure = new AtomicReference<>(null);
        // Resume support: a previously interrupted run leaves complete chunks
        // in place (zip data never starts with a zero byte, so a sparse hole
        // reads back as zeros and is treated as unfinished). Chunks already
        // fully written are skipped; only the missing ones are queued.
        java.util.Queue<Integer> pendingChunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicLong resumedBytes = new java.util.concurrent.atomic.AtomicLong(0);
        final FileChannel resumeChannel = new RandomAccessFile(dest, "rw").getChannel();
        try {
            byte[] probe = new byte[16];
            for (int idx = 0; idx < nChunks; idx++) {
                long start = idx * chunk;
                long clen = Math.min(chunk, size - start);
                int got = resumeChannel.read(java.nio.ByteBuffer.wrap(probe), start);
                boolean complete = false;
                if (got == probe.length) {
                    boolean allZero = true;
                    for (byte b : probe) if (b != 0) { allZero = false; break; }
                    if (!allZero) complete = true;
                }
                if (complete) {
                    resumedBytes.addAndGet(clen);
                } else {
                    pendingChunks.add(idx);
                }
            }
        } finally {
            resumeChannel.close();
        }
        final java.util.concurrent.atomic.AtomicBoolean rangeSupported =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        doneSum.set(resumedBytes.get());
        // Sliding-window speed: track (bytes, wallclock) of the latest chunk
        // completions so the shown rate reflects actual download throughput,
        // not the whole-run average (which is diluted by startup, queue waits
        // and inter-file gaps). Window = 3s; multi-worker completions within
        // the window accumulate before the rate is recomputed.
        final long[] speedWin = new long[4]; // {baseBytes, baseTime, accBytes, accStart}
        final Object speedLock = new Object();
        // Called on each chunk completion with the cumulative downloaded bytes
        // for this file (doneBase + doneSum). Returns a bytes/sec rate.
        // IMPORTANT: sub-second windows return 0 so concurrent 8MB chunk
        // completions can never divide by a few milliseconds and explode to
        // absurd values (240MB/s, 7GB/s).
        java.util.function.LongFunction<Long> speedFn = (doneTotal) -> {
            long now = System.currentTimeMillis();
            synchronized (speedLock) {
                if (speedWin[1] == 0) {
                    speedWin[0] = doneTotal;
                    speedWin[1] = now;
                    speedWin[3] = now;
                    return 0L;
                }
                speedWin[2] += doneTotal - speedWin[0];
                speedWin[0] = doneTotal;
                speedWin[1] = now;
                long windowMs = now - speedWin[3];
                if (windowMs < 1000) {
                    return 0L; // too short to be meaningful; keep last shown rate
                }
                if (windowMs >= 3000) {
                    long rate = (long) (speedWin[2] * 1000.0 / windowMs);
                    speedWin[2] = 0;
                    speedWin[3] = now;
                    return rate;
                }
                return (long) (speedWin[2] * 1000.0 / windowMs);
            }
        };
        final FileChannel channel = new RandomAccessFile(dest, "rw").getChannel();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    byte[] buf = new byte[1 << 16];
                    while (failure.get() == null) {
                        if (!rangeSupported.get()) return;
                        Integer queued = pendingChunks.poll();
                        if (queued == null) return;
                        int idx = queued;
                        long start = idx * chunk;
                        long end = Math.min(start + chunk, size) - 1;
                        boolean got = false;
                        for (int attempt = 0; attempt < 3 && !got; attempt++) {
                            if (attempt > 0) {
                                try { Thread.sleep(2000); }
                                catch (InterruptedException ie) { return; }
                            }
                            HttpURLConnection conn = null;
                            try {
                                conn = (HttpURLConnection) new URL(urlStr)
                                        .openConnection(systemProxy());
                                conn.setConnectTimeout(20000);
                                // 20s read budget per chunk: a slow/broken
                                // mirror stalls all 6 workers and the progress
                                // bar freezes with buttons disabled (looks like
                                // the app hung). Failing fast lets the user
                                // switch source instead of waiting 60s.
                                conn.setReadTimeout(20000);
                                conn.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
                                conn.setRequestProperty("Range",
                                        "bytes=" + start + "-" + end);
                                int code = conn.getResponseCode();
                                if (code == 200 && rangeSupported.compareAndSet(true, false)) {
                                    // No Range support: this worker downloads
                                    // the whole file once; the rest exit above.
                                    long pos = 0;
                                    try (InputStream in = new BufferedInputStream(
                                            conn.getInputStream())) {
                                        int n;
                                        while ((n = in.read(buf)) > 0) {
                                            channel.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                                            pos += n;
                                        }
                                    }
                                    if (pos != size) {
                                        throw new IOException("short download: " + pos);
                                    }
                                    doneSum.addAndGet(size - resumedBytes.get());
                                    got = true;
                                    long doneTotal = doneBase + doneSum.get();
                                    // Percent is cumulative across all files
                                    // (doneBase + current file), so the bar
                                    // advances smoothly instead of resetting
                                    // to 0% at each file boundary.
                                    int pct = total > 0 ? (int) (doneTotal * 100 / total) : 0;
                                    long rate = speedFn.apply(doneTotal);
                                    setProgress(String.format(Locale.US,
                                            "正在下载 %s  %d%%  %s / %s  (%s)",
                                            label, Math.min(99, pct), fmtSize(doneTotal),
                                            fmtSize(total),
                                            fmtSize(rate) + "/s"),
                                            Math.min(99, pct));
                                    continue;
                                }
                                if (code != 206) {
                                    throw new IOException("HTTP " + code);
                                }
                                long pos = start;
                                try (InputStream in = new BufferedInputStream(
                                        conn.getInputStream())) {
                                    int n;
                                    while ((n = in.read(buf)) > 0) {
                                        channel.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                                        pos += n;
                                    }
                                }
                                if (pos != end + 1) {
                                    throw new IOException("short chunk: " + pos);
                                }
                                long done = doneSum.addAndGet(end + 1 - start);
                                got = true;
                                // Progress text unified with the OHOS build.
                                long doneTotal = doneBase + done;
                                // Percent is cumulative across all files
                                // (doneBase + current file), so the bar
                                // advances smoothly instead of resetting
                                // to 0% at each file boundary.
                                int pct = total > 0 ? (int) (doneTotal * 100 / total) : 0;
                                long rate = speedFn.apply(doneTotal);
                                setProgress(String.format(Locale.US,
                                        "正在下载 %s  %d%%  %s / %s  (%s)",
                                        label, Math.min(99, pct), fmtSize(doneTotal),
                                        fmtSize(total),
                                        fmtSize(rate) + "/s"),
                                        Math.min(99, pct));
                            } catch (IOException e) {
                                if (attempt == 2) failure.compareAndSet(null, e);
                            } finally {
                                if (conn != null) conn.disconnect();
                            }
                        }
                        if (!got) return;
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ee) {
                    if (ee.getCause() instanceof IOException) {
                        failure.compareAndSet(null, (IOException) ee.getCause());
                    } else {
                        failure.compareAndSet(null,
                                new IOException(String.valueOf(ee.getCause())));
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载被中断", ie);
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                channel.close();
            } catch (IOException e) {
                // ignore
            }
        }
        IOException err = failure.get();
        if (err != null) {
            throw err;
        }
    }

    /** Fetches the live accelerator-node list from multiple sources so a
     *  blocked mirror (e.g. GFW/ISP) never starves the launcher:
     *  1. jsDelivr CDN (usually reachable in CN when raw is not)
     *  2. GitHub raw (second choice)
     *  Falls back to the built-in list when both fail. After a successful
     *  refresh every node is latency-probed and the fastest reachable node
     *  is auto-selected into the proxy field (only when the user has not
     *  typed a custom prefix). */
    private static void refreshAcceleratorNodes() {
        new Thread(() -> {
            final long REFRESH_MS = 10L * 60 * 1000; // re-pull node list every 10 min
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    refreshAcceleratorNodesOnce();
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(REFRESH_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }).start();
    }

    private static void refreshAcceleratorNodesOnce() {
            final String repo = "TsangAsuna/yosuga-no-sora-remake";
            // raw first: CDN mirrors (jsDelivr) can serve a stale copy after a
            // node-list change, and a stale list would show removed nodes.
            final String[] sources = {
                "https://raw.githubusercontent.com/" + repo + "/main/accelerator-nodes.json",
                "https://cdn.jsdelivr.net/gh/" + repo + "@main/accelerator-nodes.json"
            };
            String[][] loaded = null;
            for (String urlStr : sources) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new java.net.URL(urlStr).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } finally {
                        conn.disconnect();
                    }
                    JSONArray arr = new JSONObject(sb.toString()).optJSONArray("nodes");
                    if (arr == null || arr.length() == 0) continue;
                    java.util.List<String[]> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String name = o.optString("name", "").trim();
                        String prefix = o.optString("prefix", "").trim();
                        if (name.isEmpty()) continue;
                        if (!prefix.isEmpty() && !prefix.startsWith("https://")
                                && !prefix.startsWith("http://")) continue;
                        list.add(new String[]{name, prefix});
                    }
                    if (!list.isEmpty()) {
                        loaded = list.toArray(new String[0][]);
                        break;
                    }
                } catch (Exception e) {
                    // try next source
                }
            }
            if (loaded == null) return; // keep built-in fallback
            ACCEL_NODES = loaded;
            NODE_LATENCY = new long[loaded.length];
            java.util.Arrays.fill(NODE_LATENCY, -1);
            // Probe every node so the settings dialog can show live latency.
            // Deliberately NO auto-selection: the GitHub direct / GH-PROXY.CN /
            // GH-PROXY.ORG buttons keep their manual semantics (only the
            // selected one prefixes the upstream data URLs).
            if (sCurrent != null && sCurrent.proxyInput != null) {
                for (int i = 0; i < loaded.length; i++) {
                    final int nodeIndex = i;
                    try {
                        NODE_LATENCY[nodeIndex] = pingNodeLatency(loaded[nodeIndex][1]);
                    } catch (Exception ignored) {
                    }
                }
            }
    }

    /** Returns the system HTTP proxy (VPN/Clash etc.) or NO_PROXY when none
     *  is configured. Android's HttpURLConnection ignores the system proxy
     *  for non-privileged apps, so probes and downloads must apply it
     *  explicitly to honor a running proxy. */
    private static java.net.Proxy systemProxy() {
        try {
            if (sCurrent != null) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        sCurrent.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.ProxyInfo pi = cm.getDefaultProxy();
                    if (pi != null && pi.getHost() != null) {
                        return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                java.net.InetSocketAddress.createUnresolved(
                                        pi.getHost(), pi.getPort()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return java.net.Proxy.NO_PROXY;
    }

    /** Returns RTT in ms for a proxy prefix (small Range GET), or -1. */
    private static long pingNodeLatency(String proxyPrefix) {
        // Probe the host the downloader actually uses (github.com release
        // assets), NOT raw.githubusercontent.com: the two routes can differ
        // (raw blocked while github.com works, or vice versa), and a probe
        // against the wrong host would wrongly report "timeout" yet still
        // download fine. Any HTTP answer (200/206/301/404...) means the
        // prefix reaches github.com, so only connection-level failures
        // count as unreachable.
        final String probeUrl = proxyPrefix
                + "https://github.com/";
        // 1) Try through the system proxy (Clash/VPN) with a short budget:
        //    if the proxy died mid-session we must not block on its timeout.
        java.net.Proxy sys = systemProxy();
        long via = probeOnce(probeUrl, sys, 2500);
        if (via >= 0) return via;
        // 2) Proxy absent/failed: fall back to direct quickly.
        return probeOnce(probeUrl, java.net.Proxy.NO_PROXY, 6000);
    }

    /** One Range-GET probe through the given proxy; -1 on failure. */
    private static long probeOnce(String probeUrl, java.net.Proxy proxy, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new java.net.URL(probeUrl).openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            try (InputStream in = conn.getInputStream()) {
                byte[] tmp = new byte[64];
                while (in.read(tmp) >= 0) { /* drain */ }
            }
            conn.disconnect();
            if (code <= 0) return -1;
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Human-readable size, matching the OHOS fmtSize() scale. */
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void verifySha(File file, String expectedSha) throws IOException {
        if (expectedSha == null || expectedSha.isEmpty()) {
            // Trust boundary: a manifest entry without a digest must not let
            // a corrupt transfer through silently.
            throw new IOException("清单缺少 SHA-256 校验值，请更换下载源");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equalsIgnoreCase(expectedSha)) {
            throw new IOException("SHA-256 校验失败，请重试");
        }
    }

    // ---- import -------------------------------------------------------------
    private void startImport() {
        if (busy) return;
        activeAction = ACTION_IMPORT;
        setBusy(true);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null) {
            setBusy(false);
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            setBusy(false);
            return;
        }
        final List<Uri> selected = uris;
        new Thread(() -> {
            try {
                handleImport(selected);
            } catch (Exception e) {
                Log.e(TAG, "import failed", e);
                fail("导入失败：" + e.getMessage());
            } finally {
                setBusy(false);
            }
        }).start();
    }

    private void handleImport(List<Uri> uris) throws Exception {
        File parent = chooseDataParent();
        if (parent == null) {
            fail("无法使用外部存储，无法导入数据");
            return;
        }
        ensureNoMedia(parent);
        List<Uri> zips = new ArrayList<>();
        for (Uri uri : uris) {
            String name = queryName(uri);
            String lower = name == null ? "" : name.toLowerCase(Locale.US);
            if (lower.endsWith(".xp3")) {
                // data.xp3 is a COMPLETE dataset: import it alone (as before).
                setProgress("正在导入 " + name, 0);
                File xp3 = new File(parent, "data.xp3");
                copyUri(uri, xp3);
                extractXp3(xp3);
                return;
            } else if (lower.endsWith(".zip")) {
                zips.add(uri);
            } else {
                setMessage("不支持的文件类型：" + name);
            }
        }
        if (zips.isEmpty()) {
            fail("未选择有效的 zip 压缩包或 data.xp3 文件");
            return;
        }
        DataExtractService.start(this);
        try {
            // The release ships as SEVERAL INDEPENDENT zips, so imports must
            // ACCUMULATE: extract straight into the data dir WITHOUT wiping
            // it. Each archive carries its own data-assets.json at the zip
            // root (next to the data/ folder); extractZipTo drops it into
            // the data dir and processInPackManifest turns it into a
            // data-assets-<packIndex>.json record next to the data dir so
            // the completeness gate can name the missing archive numbers.
            File dataDir = new File(parent, "data");
            int[] packCount = {0};
            for (int i = 0; i < zips.size(); i++) {
                String name = queryName(zips.get(i));
                setProgress("正在解压 " + name + "（" + (i + 1) + "/" + zips.size() + "）",
                        i * 100 / zips.size());
                File zip = new File(getCacheDir(), name);
                copyUri(zips.get(i), zip);
                // A leftover in-pack manifest (from a previous DOWNLOAD that
                // drops it into the data dir, or an earlier archive of this
                // round) must never leak into THIS round's records.
                new File(dataDir, "data-assets.json").delete();
                try {
                    extractZipTo(zip, dataDir, "解压 " + name);
                } catch (IOException badZip) {
                    throw new IOException(name + " 不是有效的 zip 压缩包，请重新选择");
                }
                zip.delete();
                processInPackManifest(dataDir, parent, packCount);
            }
            finishImport(dataDir, parent, packCount[0]);
        } finally {
            DataExtractService.stop(this);
        }
    }

    /** Pick up the in-pack manifest an archive just dropped into the data
     *  dir (zip-root data-assets.json, extracted NEXT to the data/... tree)
     *  and record it as data-assets-<packIndex>.json next to the data dir.
     *  Re-importing an archive that is already recorded only notifies the
     *  user - the extracted files themselves overwrite harmlessly. */
    private void processInPackManifest(File dataDir, File parent, int[] packCount)
            throws IOException {
        File packManifest = new File(dataDir, "data-assets.json");
        if (!packManifest.isFile()) return; // legacy archive: no in-pack manifest
        JSONObject pm = null;
        try {
            pm = new JSONObject(readText(packManifest));
        } catch (Exception e) {
            Log.w(TAG, "unreadable in-pack manifest", e);
        }
        if (pm == null) return; // leave it in place for a later attempt
        int index = pm.optInt("packIndex", -1);
        int count = pm.optInt("packCount", 0);
        if (index < 1) {
            packManifest.delete(); // not a valid in-pack manifest: drop it
            return;
        }
        if (count > packCount[0]) packCount[0] = count;
        File record = new File(parent, "data-assets-" + index + ".json");
        if (record.isFile()) {
            setMessage("第 " + index + " 个压缩包已导入过，请导入其他压缩包");
            packManifest.delete(); // already recorded: drop the duplicate copy
        } else {
            if (!packManifest.renameTo(record)) {
                copyFile(packManifest, record);
                packManifest.delete();
            }
        }
    }

    /** Post-import completeness gate. Archives WITH an in-pack manifest are
     *  tracked through data-assets-<N>.json records, so the importer can
     *  tell the user the exact archive numbers still missing and only start
     *  the game once EVERY archive is in. Legacy archives (no in-pack
     *  manifest: single complete packs or old releases) fall back to
     *  startup.tjs presence and, best-effort, the release manifest's
     *  fileTotal vs the extracted file count. */
    private void finishImport(File dataDir, File parent, int observedPackCount) throws IOException {
        File innerXp3 = new File(dataDir, "data.xp3");
        if (innerXp3.isFile()) {
            // zip contained data.xp3: move it to the public root, then extract.
            setProgress("正在复制 data.xp3 到下载目录…", 0);
            File dst = new File(parent, "data.xp3");
            dst.delete();
            if (!innerXp3.renameTo(dst)) {
                copyFile(innerXp3, dst);
                innerXp3.delete();
            }
            deleteTree(dataDir);
            extractXp3(dst);
            return;
        }
        TreeSet<Integer> imported = listImportedPackIndexes(parent);
        // The release size is ALSO stored inside every record, so a round
        // where the user only re-picks an already-imported archive still
        // knows the full pack count.
        int packCount = resolveImportPackCount(parent, observedPackCount);
        if (!imported.isEmpty() && packCount > 0) {
            if (imported.size() >= packCount) {
                // Every archive of the release is in: run the normal startup.
                markConfirmed();
                final File ready = dataDir;
                runOnUi(() -> {
                    if (dataReady(ready)) startEngine(ready);
                    else fail("数据包已齐全但校验未通过，请重新导入");
                });
                return;
            }
            StringBuilder missing = new StringBuilder();
            for (int n = 1; n <= packCount; n++) {
                if (!imported.contains(n)) {
                    if (missing.length() > 0) missing.append("、");
                    missing.append(n);
                }
            }
            final String message = "数据包不完整：已导入 " + imported.size() + "/" + packCount
                    + " 个压缩包，还需导入 " + (packCount - imported.size())
                    + " 个，编号：" + missing;
            runOnUi(() -> setMessage(message));
            return;
        }
        // No in-pack manifests anywhere: a legacy single complete pack or an
        // old multi-part release. startup.tjs means the data is usable.
        if (new File(dataDir, "startup.tjs").isFile()) {
            markConfirmed();
            final File ready = dataDir;
            runOnUi(() -> {
                if (dataReady(ready)) startEngine(ready);
                else fail("数据安装后仍不可用，请重新导入");
            });
            return;
        }
        // Best-effort completeness hint from the release manifest.
        long fileTotal = -1;
        try {
            fileTotal = fetchFileTotal();
        } catch (Exception e) {
            Log.w(TAG, "fileTotal lookup failed", e);
        }
        if (fileTotal > 0) {
            final long have = countFiles(dataDir);
            final long expected = fileTotal;
            runOnUi(() -> setMessage("数据包不完整：已解压 " + have + "/" + expected
                    + " 个文件，请继续导入其余压缩包"));
        } else {
            runOnUi(() -> setMessage("数据包不完整（该压缩包不含导入进度信息），请继续导入其余压缩包"));
        }
    }

    /** Archive numbers already recorded next to the data dir, parsed from
     *  the data-assets-<N>.json file names. */
    private TreeSet<Integer> listImportedPackIndexes(File parent) {
        TreeSet<Integer> out = new TreeSet<>();
        Pattern pattern = Pattern.compile("data-assets-(\\d+)\\.json");
        File[] children = parent.listFiles();
        if (children == null) return out;
        for (File f : children) {
            Matcher m = pattern.matcher(f.getName());
            if (m.matches()) {
                try {
                    out.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    /** The release's total archive count: the highest packCount seen in
     *  THIS import round or in any previously stored record. */
    private int resolveImportPackCount(File parent, int observed) {
        int best = observed;
        for (int n : listImportedPackIndexes(parent)) {
            try {
                JSONObject pm = new JSONObject(readText(new File(parent, "data-assets-" + n + ".json")));
                int c = pm.optInt("packCount", 0);
                if (c > best) best = c;
            } catch (Exception ignored) {}
        }
        return best;
    }

    /** Recursive file count under a directory: the completeness yardstick
     *  for legacy archives without an in-pack manifest. */
    private long countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long n = 0;
        for (File f : children) {
            if (f.isDirectory()) n += countFiles(f);
            else n++;
        }
        return n;
    }

    /** Total number of files a COMPLETE dataset contains, from the release
     *  manifest: its dedicated fileTotal field, or the summed per-asset
     *  fileCount on older releases. -1 when unavailable (offline etc.). */
    private long fetchFileTotal() throws Exception {
        String base = resolveBaseUrl();
        final String proxy = proxyInput.getText().toString().trim();
        // A custom base URL is the final source (direct or mirror): never
        // stack the accelerator prefix on top of it.
        boolean customBase = !baseUrlInput.getText().toString().trim().isEmpty();
        String manifestUrl = (customBase || proxy.isEmpty())
                ? (base + "data-assets.json")
                : (proxy + base + "data-assets.json");
        HttpURLConnection conn = (HttpURLConnection) new URL(manifestUrl)
                .openConnection(systemProxy());
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "YosugaSoraHD/1.0");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            conn.disconnect();
        }
        JSONObject rootObj = new JSONObject(sb.toString());
        long total = rootObj.optLong("fileTotal", -1);
        if (total > 0) return total;
        JSONArray assets = rootObj.optJSONArray("assets");
        if (assets == null) return -1;
        long sum = 0;
        for (int i = 0; i < assets.length(); i++) {
            sum += assets.getJSONObject(i).optLong("fileCount", 0);
        }
        return sum > 0 ? sum : -1;
    }

    private String queryName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last != null ? last : "import";
        }
        return name;
    }

    private void copyUri(Uri uri, File dest) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            if (in == null) throw new IOException("无法读取所选文件");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---- extraction / install ----------------------------------------------
    private void extractZipTo(File zip, File outDir, String label) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            int total = 0;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                if (!en.nextElement().isDirectory()) total++;
            }
            int done = 0;
            byte[] buf = new byte[1 << 20];
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String rel = e.getName().replace('\\', '/');
                if (rel.startsWith("data/")) rel = rel.substring(5);
                if (rel.isEmpty() || rel.startsWith("../") || rel.contains("/../")) continue;
                File out = new File(outDir, rel);
                if (out.getParentFile() != null && !out.getParentFile().exists()
                        && !out.getParentFile().mkdirs()) {
                    throw new IOException("无法创建目录：" + out.getParentFile());
                }
                try (InputStream in = zf.getInputStream(e);
                     OutputStream fo = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                done++;
                if (done % 512 == 0 || done == total) {
                    setExtractProgress(label + "：" + done + " / " + total,
                            total > 0 ? done * 100 / total : 0);
                }
            }
        }
    }

    /** Finish a straight-into-data-dir extraction: verify the tree, or hand
     *  a zip-contained data.xp3 over to the native extractor. */
    private void installIntoDataDir(File dataDir, File parent) throws IOException {
        File innerXp3 = new File(dataDir, "data.xp3");
        if (innerXp3.isFile()) {
            // zip contained data.xp3: move it to the public root, then extract.
            setProgress("正在复制 data.xp3 到下载目录…", 0);
            File dst = new File(parent, "data.xp3");
            dst.delete();
            if (!innerXp3.renameTo(dst)) {
                copyFile(innerXp3, dst);
                innerXp3.delete();
            }
            deleteTree(dataDir);
            extractXp3(dst);
        } else if (new File(dataDir, "startup.tjs").isFile()) {
            markConfirmed();
            final File ready = dataDir;
            runOnUi(() -> {
                if (dataReady(ready)) startEngine(ready);
                else fail("数据安装后仍不可用，请重新导入");
            });
        } else {
            throw new IOException("压缩包内容不正确：未找到 data 文件夹或 data.xp3");
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdirs failed: " + dst);
            String[] children = src.list();
            if (children == null) return;
            for (String child : children) copyTree(new File(src, child), new File(dst, child));
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private void markConfirmed() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_CONFIRMED_VERSION, getVersionCode()).apply();
        } catch (Exception ignored) {}
    }

    // ---- xp3 extraction -----------------------------------------------------
    private void extractXp3(File xp3) throws IOException {
        File parent = chooseDataParent();
        if (parent == null) throw new IOException("无法使用外部存储");
        File tmp = new File(parent, "data.extract.tmp");
        File dataDir = new File(parent, "data");
        deleteTree(tmp);
        DataExtractService.start(this);
        try {
            setExtractProgress("正在解包 data.xp3…", 0);
            boolean started = nativeExtractXp3Start(xp3.getAbsolutePath(), tmp.getAbsolutePath());
            if (!started) throw new IOException("无法启动解包线程");
            while (true) {
                sleepQuietly(400);
                String status = readText(new File(tmp.getAbsolutePath() + ".status"));
                if (status != null && !status.isEmpty()) {
                    status = status.trim();
                    if (status.startsWith("ok")) break;
                    String err = status.startsWith("error") ? status.substring(5).trim() : status;
                    throw new IOException("解包失败：" + (err.isEmpty() ? "未知错误" : err));
                }
                String progress = readText(new File(tmp.getAbsolutePath() + ".progress"));
                if (progress != null && !progress.isEmpty()) {
                    String[] parts = progress.trim().split(" ");
                    if (parts.length >= 2) {
                        try {
                            int done = Integer.parseInt(parts[0]);
                            int total = Integer.parseInt(parts[1]);
                            setExtractProgress("正在解包 data.xp3：" + done + " / " + total,
                                    total > 0 ? done * 100 / total : 0);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (dataDir.exists()) deleteTree(dataDir);
            if (!tmp.renameTo(dataDir)) {
                setProgress("正在移动数据到数据目录…", 100);
                copyTree(tmp, dataDir);
                deleteTree(tmp);
            }
            xp3.delete();
            new File(tmp.getAbsolutePath() + ".status").delete();
            new File(tmp.getAbsolutePath() + ".progress").delete();
            markConfirmed();
            final File ready = dataDir;
            runOnUi(() -> {
                if (dataReady(ready)) startEngine(ready);
                else fail("解包完成但数据不可用");
            });
        } finally {
            DataExtractService.stop(this);
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteTree(c);
            }
        }
        f.delete();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        runOnUi(() -> {
            setMessage(message);
            setProgress("", 0);
        });
    }

    // ---- JNI ----------------------------------------------------------------
    private static native boolean nativeExtractXp3Start(String xp3Path, String outDir);

    static {
        // "SDL2" first (its symbols are needed by the engine), then the
        // engine library itself - the native methods of this class live in
        // libmain.so. Best-effort: a library problem must never crash the
        // bootstrap page itself.
        try {
            System.loadLibrary("SDL2");
        } catch (Throwable ignored) {
            Log.e(TAG, "SDL2 library missing", ignored);
        }
        try {
            System.loadLibrary("main");
        } catch (Throwable ignored) {
            Log.e(TAG, "main library missing", ignored);
        }
    }
}
