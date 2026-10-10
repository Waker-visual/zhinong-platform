package app.zhinong.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** A dedicated mobile client hosted by the farm server; no passwords, tokens or device keys in the APK. */
public final class MainActivity extends Activity {
    private WebView web;
    private String origin;
    private int generation;
    private FrameLayout frame;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(getColor(R.color.text));
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private Button button(String title) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setBackgroundResource(R.drawable.primary_button); b.setTextColor(getColor(R.color.cream)); return b; }
    private LinearLayout layout() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.page)); return root;
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showConnection();
    }
    private void destroyWeb() {
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.clearCache(true); web.destroy(); web = null; }
        hideFullscreen();
    }
    private void showConnection() {
        generation++; destroyWeb();
        LinearLayout root = layout(); root.setPadding(dp(24), dp(28), dp(24), dp(24));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.app_icon); mark.setContentDescription("智禾农场标志");
        root.addView(mark, new LinearLayout.LayoutParams(dp(64), dp(64)));
        root.addView(text("智禾随行", 30)); root.addView(text("田间有你，农场在手边。", 17));
        ImageView scene = new ImageView(this); scene.setImageResource(R.drawable.field_scene); scene.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); scene.setAdjustViewBounds(true);
        root.addView(scene, new LinearLayout.LayoutParams(-1, dp(135)));
        root.addView(text("服务器地址", 14));
        EditText address = new EditText(this); address.setSingleLine(true); address.setInputType(17);
        address.setHint("https://farm.example.com");
        address.setText(getPreferences(MODE_PRIVATE).getString("server_origin", "")); root.addView(address);
        root.addView(text("手机和电脑需能访问同一个后端。局域网使用电脑的网络地址和端口；不要填写手机自身的 127.0.0.1。", 13));
        TextView result = text("仅保存服务器地址，登录后按农场账号权限查看数据。", 13); root.addView(result);
        Button connect = button("检查连接并进入"); root.addView(connect);
        root.addView(text("只需保持农场服务器在线，无须打开电脑网页监督。田间跨网络使用时，请连接已部署的 HTTPS 服务。", 12));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        connect.setOnClickListener(v -> {
            final String selected;
            try { selected = ServerAddress.normalize(address.getText().toString()); }
            catch (IllegalArgumentException e) { result.setText(e.getMessage()); return; }
            final int current = ++generation; connect.setEnabled(false); result.setText("正在检查服务器与手机页面…");
            new Thread(() -> {
                String problem = null;
                try {
                    String health = read(selected + "/api/health");
                    if (!health.matches("(?s).*\"ready\"\\s*:\\s*true.*")) throw new Exception("服务尚未就绪");
                    if (!read(selected + "/mobile/index.html").contains("zhinong-mobile")) throw new Exception("服务器尚未部署智禾手机页面，请先更新主项目");
                } catch (Exception e) { problem = "连接失败：" + e.getMessage(); }
                final String failure = problem;
                runOnUiThread(() -> { if (current != generation || isFinishing()) return; connect.setEnabled(true);
                    if (failure != null) result.setText(failure);
                    else { origin = selected; getPreferences(MODE_PRIVATE).edit().putString("server_origin", origin).apply(); showFarm(); }
                });
            }, "zhihe-connection-check").start();
        });
    }
    private String read(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode() + "，请核对地址和服务状态");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int length;
                while ((length = in.read(buf)) >= 0) { if (out.size() + length > 65536) throw new Exception("服务响应过大"); out.write(buf, 0, length); }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    private void showFarm() {
        LinearLayout root = layout();
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(dp(12), 0, dp(8), 0);
        TextView host = text(origin, 11); host.setSingleLine(true); host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(host, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button settings = button("连接设置"); settings.setTextSize(11); toolbar.addView(settings);
        settings.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("返回连接设置？").setMessage("将关闭当前页面，重新进入需登录。已下发的任务会继续执行，可重新登录查看结果。").setNegativeButton("继续查看", null).setPositiveButton("返回设置", (d, w) -> showConnection()).show());
        root.addView(toolbar);
        frame = new FrameLayout(this); root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this); frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WebSettings cfg = web.getSettings(); cfg.setJavaScriptEnabled(true); cfg.setDomStorageEnabled(false); cfg.setAllowFileAccess(false); cfg.setAllowContentAccess(false);
        cfg.setAllowFileAccessFromFileURLs(false); cfg.setAllowUniversalAccessFromFileURLs(false); cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        cfg.setCacheMode(WebSettings.LOAD_NO_CACHE); cfg.setSaveFormData(false); cfg.setMediaPlaybackRequiresUserGesture(true); cfg.setSafeBrowsingEnabled(true);
        cfg.setUserAgentString(cfg.getUserAgentString() + " ZhiheMobile/1.1");
        CookieManager.getInstance().setAcceptCookie(false); CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                boolean allowed = ServerAddress.sameOrigin(origin, request.getUrl().toString());
                if (!allowed) Toast.makeText(MainActivity.this, "仅允许访问已配置的农场服务器", Toast.LENGTH_SHORT).show();
                return !allowed;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError e) { if (request.isForMainFrame()) pageFailure("页面无法连接，请检查网络或服务器。"); }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) { if (request.isForMainFrame()) pageFailure("页面返回 HTTP " + response.getStatusCode()); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) { if (fullscreen != null) { callback.onCustomViewHidden(); return; } fullscreen = view; fullscreenCallback = callback; frame.addView(view, new FrameLayout.LayoutParams(-1, -1)); web.setVisibility(View.GONE); }
            @Override public void onHideCustomView() { hideFullscreen(); }
        });
        setContentView(root); web.loadUrl(origin + "/mobile/index.html");
    }
    private void pageFailure(String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("连接中断").setMessage(message + " 不会自动重发指令。")
            .setNegativeButton("连接设置", (d, w) -> showConnection()).setPositiveButton("重新加载页面", (d, w) -> { if (web != null) web.loadUrl(origin + "/mobile/index.html"); }).show();
    }
    private void hideFullscreen() {
        if (fullscreen != null) { frame.removeView(fullscreen); fullscreen = null; if (web != null) web.setVisibility(View.VISIBLE); if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden(); fullscreenCallback = null; }
    }
    @Override public void onBackPressed() {
        if (fullscreen != null) hideFullscreen();
        else if (web != null) new AlertDialog.Builder(this).setTitle("离开智禾随行？").setMessage("已下发任务会继续执行；下次进入可查看记录。").setNegativeButton("继续查看", null).setPositiveButton("退出", (d, w) -> finish()).show();
        else super.onBackPressed();
    }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { generation++; destroyWeb(); super.onDestroy(); }
}
