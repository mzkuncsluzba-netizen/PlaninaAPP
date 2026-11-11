package si.planina.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.ambitsyncmodern.ble.AmbitBleModule;
import com.ambitsyncmodern.usb.AmbitUsbModule;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int REQ_OPEN = 1, REQ_SAVE = 2, REQ_LOC = 3, REQ_BLE = 4;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private String pendingText;
    private String pendingImport, pendingImportName;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Keep the screen on while the app is open, so GPS keeps running during a hike.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#c8102e"));

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);

        dropStaleLocalPage();
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new PageHandler())
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverImport();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) {
                    cb.invoke(origin, true, false);
                } else {
                    geoCallback = cb;
                    geoOrigin = origin;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Izberi datoteko .gpx"), REQ_OPEN);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "PlaninaAndroid");

        if (saved != null) web.restoreState(saved);
        else web.loadUrl(START);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Reads a .gpx shared or opened from another app (e.g. the Suunto app) and hands it to the page. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        String inlineText = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) inlineText = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else {
            return;
        }
        intent.setAction(Intent.ACTION_MAIN); // don't import the same file again after rotation

        String name = "aktivnost.gpx";
        String text = inlineText;
        if (uri != null) {
            name = displayName(uri);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > 40 * 1024 * 1024) break;
                }
                byte[] all = buf.toByteArray();
                if (all.length > 12 && all[8] == '.' && all[9] == 'F' && all[10] == 'I' && all[11] == 'T') {
                    Toast.makeText(this, "To je datoteka FIT. V aplikaciji Suunto izberi izvoz v GPX.", Toast.LENGTH_LONG).show();
                    return;
                }
                text = new String(all, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Toast.makeText(this, "Datoteke ni bilo mogoče prebrati.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (text == null || !text.contains("<gpx")) {
            Toast.makeText(this, "To ni datoteka .gpx.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingImport = text;
        pendingImportName = name;
        deliverImport();
    }

    private void deliverImport() {
        if (!pageReady || pendingImport == null) return;
        String js = "window.planinaImport&&window.planinaImport(" + JSONObject.quote(pendingImport)
                + "," + JSONObject.quote(pendingImportName) + ")";
        pendingImport = null;
        web.evaluateJavascript(js, null);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last != null ? last : "aktivnost.gpx";
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /* ---------- in-app updates: the page is downloaded from GitHub Pages and kept on the phone ---------- */
    private static final String PAGE_URL = "https://mzkuncsluzba-netizen.github.io/PlaninaAPP/index.html";
    private static final Pattern VERSION = Pattern.compile("<meta name=\"planina-version\" content=\"(\\d+)\"");

    private File localPage() { return new File(getFilesDir(), "web/index.html"); }

    private static int parseVersion(String html) {
        Matcher m = VERSION.matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private int bundledVersion() {
        try (InputStream in = getAssets().open("index.html")) { return parseVersion(readAll(in)); }
        catch (Exception e) { return 0; }
    }

    private int localVersion() {
        File f = localPage();
        if (!f.exists()) return 0;
        try (InputStream in = new FileInputStream(f)) { return parseVersion(readAll(in)); }
        catch (Exception e) { return 0; }
    }

    private int currentVersion() { return Math.max(bundledVersion(), localVersion()); }

    /** A newer APK may carry a newer page than the downloaded copy; then the copy goes. */
    private void dropStaleLocalPage() {
        File f = localPage();
        if (f.exists() && localVersion() < bundledVersion()) f.delete();
    }

    private class PageHandler implements WebViewAssetLoader.PathHandler {
        private final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(MainActivity.this);

        @Override
        public WebResourceResponse handle(String path) {
            if ("index.html".equals(path)) {
                File f = localPage();
                if (f.exists()) {
                    try { return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f)); }
                    catch (Exception ignored) { }
                }
            }
            return assets.handle(path);
        }
    }

    private void checkUpdate(final boolean manual) {
        new Thread(() -> {
            String result;
            int remote = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(PAGE_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setUseCaches(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                String html;
                try (InputStream in = c.getInputStream()) { html = readAll(in); }
                remote = parseVersion(html);
                if (remote <= 0 || !html.contains("<title>PlaninaAPP</title>")) {
                    result = "error";
                } else if (remote <= currentVersion()) {
                    result = "current";
                } else {
                    File f = localPage();
                    f.getParentFile().mkdirs();
                    File tmp = new File(f.getParentFile(), "index.html.tmp");
                    try (OutputStream os = new FileOutputStream(tmp)) { os.write(html.getBytes(StandardCharsets.UTF_8)); }
                    if (!tmp.renameTo(f)) throw new Exception("rename");
                    result = "updated";
                }
            } catch (Exception e) {
                result = "offline";
            }
            final String js = "window.planinaUpdateResult&&window.planinaUpdateResult('" + result + "'," + remote + "," + manual + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }).start();
    }

    /* ---------- Suunto Ambit over Bluetooth (protocol from Sommet, GPLv3) ---------- */
    private AmbitBleModule watch;
    private AmbitUsbModule watchData;
    private final ExecutorService watchExec = Executors.newSingleThreadExecutor();
    private volatile boolean watchSyncing = false;
    private String[] watchKnownIds = new String[0];
    private boolean watchLibOk = true;

    private void watchEvent(String type, String data) {
        final String js = "window.planinaWatch&&window.planinaWatch(" + JSONObject.quote(type) + ","
                + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private boolean ensureWatch() {
        if (watch != null) return true;
        try {
            watchData = new AmbitUsbModule();
            watch = new AmbitBleModule(this, new AmbitBleModule.Listener() {
                @Override public void onStatus(String code) { watchEvent("status", code); }
                @Override public void onConnected(String name) { watchEvent("connected", name); startWatchSync(); }
                @Override public void onFailed(String code, String message) { watchEvent("error", message); }
                @Override public void onDisconnected() { if (!watchSyncing) watchEvent("disconnected", ""); }
                @Override public void onSyncRequested() { startWatchSync(); }
            });
            return true;
        } catch (Throwable t) {
            watchLibOk = false;
            watchEvent("error", "Knjižnica za uro se ni naložila na tem telefonu.");
            return false;
        }
    }

    private String[] blePermissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasBlePermissions() {
        for (String p : blePermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void watchConnect() {
        if (!ensureWatch()) return;
        if (!hasBlePermissions()) { requestPermissions(blePermissions(), REQ_BLE); return; }
        watch.scanAndConnect();
    }

    private void startWatchSync() {
        if (watchSyncing || watch == null || !watch.isLive()) return;
        watchSyncing = true;
        watchEvent("syncing", "");
        watchExec.execute(() -> {
            int imported = 0;
            boolean lock = false;
            try {
                try { lock = watchData.nativeAmbitSyncDisplay(true); } catch (Throwable ignored) { }
                int n = watchData.nativeAmbitGetLogCount(watchKnownIds);
                if (n < 0) {
                    watchEvent("error", "Branje z ure ni uspelo. Poskusi znova.");
                } else {
                    for (int i = 0; i < n; i++) {
                        String gpx = watchData.nativeAmbitGetLogAsGpx(i);
                        if (gpx != null) { watchEvent("gpx", gpx); imported++; }
                        watchEvent("progress", (i + 1) + "/" + n);
                    }
                    watchEvent("done", String.valueOf(imported));
                }
            } catch (Throwable t) {
                watchEvent("error", "Med branjem ure je prišlo do napake: " + t.getMessage());
            } finally {
                if (lock) { try { watchData.nativeAmbitSyncDisplay(false); } catch (Throwable ignored) { } }
                try { watchData.nativeAmbitDisconnect(); } catch (Throwable ignored) { }
                watchSyncing = false;
                runOnUiThread(() -> { if (watch != null) watch.disconnect(); });
            }
        });
    }

    /** Called from the page to save an exported .gpx through Android's own "Save as" screen. */
    private class Bridge {
        @JavascriptInterface
        public boolean watchAvailable() { return watchLibOk && getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE); }

        /** knownIdsJson: ["YYYYMMDDTHHMMSS", ...] of moves already in the app, so the watch skips them. */
        @JavascriptInterface
        public void watchConnect(String knownIdsJson) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(knownIdsJson == null ? "[]" : knownIdsJson);
                String[] ids = new String[a.length()];
                for (int i = 0; i < a.length(); i++) ids[i] = a.optString(i, "");
                watchKnownIds = ids;
            } catch (Exception e) { watchKnownIds = new String[0]; }
            runOnUiThread(MainActivity.this::watchConnect);
        }

        @JavascriptInterface
        public void watchCancel() {
            runOnUiThread(() -> { if (watch != null && !watchSyncing) { watch.disconnect(); watchEvent("disconnected", ""); } });
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) { MainActivity.this.checkUpdate(manual); }

        @JavascriptInterface
        public String apkVersion() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        @JavascriptInterface
        public void saveFile(final String name, final String text) {
            runOnUiThread(() -> {
                pendingText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/gpx+xml");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Shranjevanje ni na voljo.", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCallback = null;
            }
        } else if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingText != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    os.write(pendingText.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Datoteka shranjena.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Datoteke ni bilo mogoče shraniti.", Toast.LENGTH_LONG).show();
                }
            }
            pendingText = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOC && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        } else if (req == REQ_BLE) {
            if (hasBlePermissions()) watchConnect();
            else watchEvent("error", "Brez dovoljenja za Bluetooth se z uro ne morem povezati.");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
