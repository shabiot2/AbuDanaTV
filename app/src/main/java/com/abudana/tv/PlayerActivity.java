package com.abudana.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen native player for IPTV channels, movies and episodes.
 * Live: ▲ / ▼ (or CH+ / CH-) switch channels, OK shows the info bar, Back returns to the list.
 * Movies / episodes: the standard player controls; the next episode starts on its own.
 */
public class PlayerActivity extends Activity {
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    static List<String[]> items = new ArrayList<>(); // {title, url, logo}
    static int startIndex = 0;
    static boolean live = true;
    private static WeakReference<PlayerActivity> current = new WeakReference<>(null);

    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout osd;
    private TextView osdNum, osdTitle, osdSub;
    private int index;
    private int retries;
    private boolean triedTs;
    private String currentUrl = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsd = () -> { if (osd != null) osd.animate().alpha(0f).setDuration(350).start(); };

    /** Called when the adhan alert needs the main screen in front. */
    static void closeIfOpen() {
        PlayerActivity p = current.get();
        if (p != null && !p.isFinishing()) p.finish();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (items == null || items.isEmpty()) { finish(); return; }
        current = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(!live);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setKeepScreenOn(true);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildOsd(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (player == null) return;
                if (retries < 1) {
                    retries++;
                    handler.postDelayed(() -> { if (player != null) { player.prepare(); player.play(); } }, 1500);
                    return;
                }
                // Some servers only serve live channels as .ts — try that once before giving up.
                if (live && !triedTs && currentUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                    triedTs = true;
                    load(currentUrl.replace(".m3u8", ".ts"));
                    return;
                }
                showOsd(null, live ? "تعذّر تشغيل القناة · جرّب قناة ثانية بـ ▲ ▼" : "تعذّر تشغيل هذا المحتوى", false);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) retries = 0;
                if (state == Player.STATE_ENDED && !live && index < items.size() - 1) play(index + 1);
            }
        });
        play(startIndex);
        view.requestFocus();
    }

    private View buildOsd() {
        osd = new LinearLayout(this);
        osd.setOrientation(LinearLayout.HORIZONTAL);
        osd.setGravity(Gravity.CENTER_VERTICAL);
        osd.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        osd.setPadding(dp(48), dp(64), dp(48), dp(36));
        osd.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        Typeface tf = null;
        try { tf = Typeface.createFromAsset(getAssets(), "fonts/ReadexPro.ttf"); } catch (Exception ignored) { }

        osdNum = new TextView(this);
        osdNum.setTextColor(0xFFF6C76E);
        osdNum.setTextSize(34);
        if (tf != null) osdNum.setTypeface(tf, Typeface.BOLD);
        LinearLayout.LayoutParams lpNum = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpNum.setMarginEnd(dp(28));
        osd.addView(osdNum, lpNum);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        osdTitle = new TextView(this);
        osdTitle.setTextColor(Color.WHITE);
        osdTitle.setTextSize(28);
        osdTitle.setSingleLine(true);
        osdTitle.setEllipsize(TextUtils.TruncateAt.END);
        if (tf != null) osdTitle.setTypeface(tf, Typeface.BOLD);
        osdSub = new TextView(this);
        osdSub.setTextColor(0xCCFFFFFF);
        osdSub.setTextSize(16);
        if (tf != null) osdSub.setTypeface(tf);
        col.addView(osdTitle);
        col.addView(osdSub);
        osd.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return osd;
    }

    private void play(int i) {
        if (player == null) return;
        int n = items.size();
        index = ((i % n) + n) % n;
        retries = 0;
        triedTs = false;
        String[] it = items.get(index);
        load(it[1]);
        osdNum.setText(live ? String.format(new Locale("ar"), "%d", index + 1) : "");
        showOsd(it[0], live ? "▲ ▼ لتغيير القناة · رجوع للقائمة" : "OK للتحكم · رجوع للقائمة", true);
    }

    private void load(String url) {
        if (player == null) return;
        currentUrl = url == null ? "" : url;
        MediaItem.Builder b = new MediaItem.Builder().setUri(currentUrl);
        if (currentUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void showOsd(String title, String sub, boolean autoHide) {
        if (osd == null) return;
        if (title != null) osdTitle.setText(title);
        if (sub != null) osdSub.setText(sub);
        osd.animate().cancel();
        osd.setAlpha(1f);
        handler.removeCallbacks(hideOsd);
        if (autoHide) handler.postDelayed(hideOsd, 4500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!live && view != null && view.isControllerFullyVisible()) view.hideController();
                else finish();
            }
            return true;
        }
        if (live) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_CHANNEL_DOWN:
                        play(index - 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_CHANNEL_UP:
                        play(index + 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_INFO:
                        showOsd(null, null, true);
                        return true;
                    default:
                        break;
                }
            }
            return super.dispatchKeyEvent(e);
        }
        return (view != null && view.dispatchKeyEvent(e)) || super.dispatchKeyEvent(e);
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    @Override
    protected void onStop() {
        super.onStop();
        release();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
        if (current.get() == this) current = new WeakReference<>(null);
    }
}
