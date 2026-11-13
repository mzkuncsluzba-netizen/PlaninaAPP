package si.planina.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.LocationManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
        registerInstallReceiver();

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

    /* ---------- in-app APK updates: the newest GitHub release is downloaded and installed ---------- */
    private static final String RELEASE_API = "https://api.github.com/repos/mzkuncsluzba-netizen/PlaninaAPP/releases/latest";
    private static final String APK_URL = "https://github.com/mzkuncsluzba-netizen/PlaninaAPP/releases/download/v%d/PlaninaAPP.apk";
    private static final String ACTION_INSTALLED = "si.planina.app.APK_INSTALLED";
    private volatile int apkLatest = 0;
    private volatile boolean apkBusy = false;
    private boolean apkWaitingForPermission = false;
    private BroadcastReceiver installReceiver;

    private void apkEvent(String type, String data) {
        final String js = "window.planinaApk&&window.planinaApk(" + JSONObject.quote(type) + ","
                + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private int installedVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Asks GitHub for the newest release (tag "v<build number>") and tells the page whether it is newer. */
    private void checkApk(final boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setUseCaches(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                String body;
                try (InputStream in = c.getInputStream()) { body = readAll(in); }
                String tag = new JSONObject(body).optString("tag_name", "");
                int latest = Integer.parseInt(tag.replaceAll("\\D", ""));
                apkLatest = latest;
                if (latest > installedVersionCode()) apkEvent("available", String.valueOf(latest));
                else if (manual) apkEvent("current", String.valueOf(installedVersionCode()));
            } catch (Exception e) {
                if (manual) apkEvent("offline", "");
            }
        }).start();
    }

    private void installApk() {
        if (apkBusy) return;
        if (apkLatest <= 0) { checkApk(true); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            apkWaitingForPermission = true;
            apkEvent("permission", "");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        apkBusy = true;
        final int ver = apkLatest;
        new Thread(() -> {
            File apk = new File(getCacheDir(), "PlaninaAPP.apk");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(String.format(java.util.Locale.ROOT, APK_URL, ver)).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong();
                long done = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(apk)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        os.write(b, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) { lastPct = pct; apkEvent("downloading", pct >= 0 ? String.valueOf(pct) : ""); }
                    }
                }
                if (total > 0 && done != total) throw new Exception("prekinjen prenos");

                apkEvent("installing", "");
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream os = s.openWrite("PlaninaAPP.apk", 0, apk.length())) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) os.write(b, 0, n);
                        s.fsync(os);
                    }
                    Intent done2 = new Intent(ACTION_INSTALLED).setPackage(getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent p = PendingIntent.getBroadcast(this, id, done2, flags);
                    s.commit(p.getIntentSender());
                }
            } catch (Exception e) {
                apkBusy = false;
                apkEvent("error", "Posodobitve ni bilo mogoče prenesti: " + e.getMessage());
            }
        }).start();
    }

    /** Result of the install session: Android first asks the user to confirm, then reports the outcome. */
    private void registerInstallReceiver() {
        installReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(confirm); } catch (Exception ignored) { }
                    }
                    apkEvent("confirm", "");
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    apkBusy = false;
                    apkEvent("done", "");
                } else {
                    apkBusy = false;
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    apkEvent(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "error",
                            status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                                    ? "Nova verzija je podpisana drugače kot nameščena. Enkrat jo namesti ročno z GitHuba (staro prej odstrani)."
                                    : "Namestitev ni uspela" + (msg != null ? ": " + msg : "."));
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_INSTALLED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (apkWaitingForPermission) {
            apkWaitingForPermission = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) installApk();
            else apkEvent("error", "Brez dovoljenja »Nameščanje neznanih aplikacij« se aplikacija ne more posodobiti sama.");
        }
    }

    @Override
    protected void onDestroy() {
        if (installReceiver != null) { try { unregisterReceiver(installReceiver); } catch (Exception ignored) { } }
        super.onDestroy();
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

    // BLUETOOTH_SCAN is declared without "neverForLocation", so Android only hands over scan
    // results when location is granted too (and on every version, when location is switched on).
    private String[] blePermissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{"android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT", Manifest.permission.ACCESS_FINE_LOCATION};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasBlePermissions() {
        for (String p : blePermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void watchConnect() {
        if (!ensureWatch()) return;
        if (!hasBlePermissions()) { requestPermissions(blePermissions(), REQ_BLE); return; }
        if (!locationOn()) {
            watchEvent("error", "Vklopi lokacijo na telefonu, sicer Android ure ne najde. Nato pritisni »Prenesi z ure« znova.");
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { }
            return;
        }
        watch.scanAndConnect();
    }

    private void startWatchSync() {
        if (watchSyncing || watch == null || !watch.isLive()) return;
        watchSyncing = true;
        watchEvent("syncing", "");
        // Reading every move over Bluetooth is slow (minutes on the first sync), so show what is
        // happening once a second: which move, out of how many, and the time spent so far.
        final long t0 = System.currentTimeMillis();
        final Handler ticker = new Handler(Looper.getMainLooper());
        final boolean[] reading = {true};
        ticker.postDelayed(new Runnable() {
            @Override public void run() {
                if (!reading[0]) return;
                int cur = 0, total = 0;
                try { int[] p = watchData.nativeAmbitLogProgress(); if (p != null && p.length >= 2) { cur = p[0]; total = p[1]; } }
                catch (Throwable ignored) { }
                long s = (System.currentTimeMillis() - t0) / 1000;
                String time = (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
                String what = total > 0
                        ? "Berem z ure: aktivnost " + Math.min(cur + 1, total) + " od " + total
                        : "Berem seznam aktivnosti z ure";
                watchEvent("status", what + " (" + time + "). Prvi prenos lahko traja dlje, ura naj bo blizu telefona.");
                ticker.postDelayed(this, 1000);
            }
        }, 1000);
        watchExec.execute(() -> {
            int imported = 0;
            boolean lock = false;
            try {
                try { lock = watchData.nativeAmbitSyncDisplay(true); } catch (Throwable ignored) { }
                int n;
                try { n = watchData.nativeAmbitGetLogCount(watchKnownIds); }
                finally { runOnUiThread(() -> reading[0] = false); }
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
        public void checkApk(boolean manual) { MainActivity.this.checkApk(manual); }

        @JavascriptInterface
        public void installApk() { runOnUiThread(MainActivity.this::installApk); }

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
            else watchEvent("error", "Za iskanje ure dovoli »Naprave v bližini« in »Lokacija« (Nastavitve → Aplikacije → PlaninaAPP → Dovoljenja).");
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
