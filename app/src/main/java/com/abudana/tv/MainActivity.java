package com.abudana.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#04060c"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The page declares a fixed 1920px-wide viewport; the WebView fits that canvas to the
        // real screen, whatever resolution or density the TV reports.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:")) return false;
                openExternal(url);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setFocusable(true);
        web.requestFocus();
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openExternal(String url) {
        Uri uri = Uri.parse(url);
        // Prefer the YouTube app on Android TV / Google TV
        String[] pkgs = {"com.google.android.youtube.tv", "com.google.android.youtube"};
        for (String p : pkgs) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage(p);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) { }
    }

    public class Bridge {
        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            Map<String, JSONObject> out = new LinkedHashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("pkg", pkg);
                        o.put("label", String.valueOf(ri.loadLabel(pm)));
                        Drawable banner = null;
                        try { banner = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
                        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
                        o.put("banner", banner != null);
                        o.put("icon", toBase64(banner != null ? banner : ri.loadIcon(pm), banner != null));
                        out.put(pkg, o);
                    } catch (Exception ignored) { }
                }
            }
            JSONArray arr = new JSONArray();
            for (JSONObject o : out.values()) arr.put(o);
            return arr.toString();
        }

        @JavascriptInterface
        public void launchApp(final String pkg) {
            runOnUiThread(() -> {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); } catch (Exception ignored) { }
                }
            });
        }

        /** Opens the native player with a list of {t: title, u: url, l: logo}. */
        @JavascriptInterface
        public void playList(String json, int index, boolean live) {
            try {
                JSONArray arr = new JSONArray(json);
                ArrayList<String[]> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new String[]{o.optString("t"), o.optString("u"), o.optString("l")});
                }
                if (list.isEmpty()) return;
                PlayerActivity.items = list;
                PlayerActivity.startIndex = Math.max(0, Math.min(index, list.size() - 1));
                PlayerActivity.live = live;
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PlayerActivity.class)));
            } catch (Exception ignored) { }
        }

        /** Brings this screen back in front of the player so the adhan alert is seen. */
        @JavascriptInterface
        public void adhanFront() {
            runOnUiThread(PlayerActivity::closeIfOpen);
        }

        /** Fetches a URL natively (no browser CORS limits) and hands the text back to the page. */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            new Thread(() -> {
                int code = 0;
                String body = null;
                try {
                    String target = url;
                    for (int hop = 0; hop < 6; hop++) {
                        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", PlayerActivity.UA);
                        c.setRequestProperty("Accept", "*/*");
                        code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            if (loc == null) break;
                            target = new URL(new URL(target), loc).toString();
                            continue;
                        }
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        if (in != null) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                            in.close();
                            body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                        }
                        c.disconnect();
                        break;
                    }
                } catch (Exception e) {
                    code = 0;
                    body = null;
                }
                final String js = "window.__abuHttp&&window.__abuHttp(" + JSONObject.quote(id) + "," + code + ","
                        + (body == null ? "null" : JSONObject.quote(body)) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }).start();
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) { }
            });
        }
    }

    private static String toBase64(Drawable d, boolean banner) {
        int w = banner ? 320 : 144, h = banner ? 180 : 144;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // refresh the apps row when something is installed or removed
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (web != null) web.evaluateJavascript("window.abuDanaAppsChanged && window.abuDanaAppsChanged()", null);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Back closes the open panel inside the page; on the home screen it does nothing (launcher behaviour)
            if (event.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.abuDanaBack && window.abuDanaBack()", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'));window.abuDanaAppsChanged&&window.abuDanaAppsChanged()", null);
    }

    @Override
    protected void onPause() {
        // keep timers alive so the adhan alert still fires
        super.onPause();
    }
}
