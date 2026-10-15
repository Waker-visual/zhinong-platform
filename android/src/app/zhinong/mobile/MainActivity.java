package app.zhinong.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(); background.setColor(getColor(R.color.surface));
        background.setStroke(dp(1), getColor(R.color.border)); background.setCornerRadius(dp(20));
        panel.setBackground(background); return panel;
    }
    private LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom); return params;
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
        LinearLayout root = layout(); root.setPadding(dp(20), dp(18), dp(20), dp(24));
        LinearLayout header = new LinearLayout(this); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.app_icon); mark.setContentDescription("智禾农场标志");
        header.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL); brand.setPadding(dp(12), 0, 0, 0);
        TextView brandName = text("智禾随行", 25); brandName.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView brandCaption = text("田间工作伙伴", 12); brandCaption.setTextColor(getColor(R.color.muted));
        brand.addView(brandName); brand.addView(brandCaption); header.addView(brand);
        root.addView(header, margins(0, 24));

        TextView eyebrow = text("连接你的农场", 13); eyebrow.setTextColor(getColor(R.color.brand)); eyebrow.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(eyebrow, margins(0, 4));
        TextView title = text("把田间工作带在身边", 26); title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, margins(0, 2));
        TextView intro = text("手机和电脑共享同一后端，先配置服务器地址，再使用农场账号登录。", 14); intro.setTextColor(getColor(R.color.muted));
        root.addView(intro, margins(0, 14));

        FrameLayout scenePanel = new FrameLayout(this);
        GradientDrawable sceneBackground = new GradientDrawable(); sceneBackground.setColor(getColor(R.color.seed)); sceneBackground.setCornerRadius(dp(20)); scenePanel.setBackground(sceneBackground);
        ImageView scene = new ImageView(this); scene.setImageResource(R.drawable.field_scene); scene.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); scene.setAdjustViewBounds(true);
        scenePanel.addView(scene, new FrameLayout.LayoutParams(-1, dp(132)));
        root.addView(scenePanel, margins(0, 16));

        LinearLayout connection = panel();
        TextView serverLabel = text("服务器地址", 13); serverLabel.setTypeface(null, android.graphics.Typeface.BOLD); connection.addView(serverLabel);
        EditText address = new EditText(this); address.setSingleLine(true); address.setInputType(17); address.setTextSize(16); address.setHint("http://电脑的局域网地址:9175"); address.setPadding(0, dp(8), 0, dp(8));
        address.setText(getPreferences(MODE_PRIVATE).getString("server_origin", "")); connection.addView(address, margins(4, 4));
        TextView hint = text("手机和电脑需访问同一个后端。局域网请填写电脑的网络地址和端口，不要填写手机自身的 127.0.0.1。", 12); hint.setTextColor(getColor(R.color.muted)); connection.addView(hint, margins(0, 4));
        TextView result = text("仅保存服务器地址，登录后按农场账号权限查看数据。", 12); result.setTextColor(getColor(R.color.muted)); connection.addView(result, margins(0, 8));
        Button connect = button("检查连接并进入"); connect.setTextSize(16); connection.addView(connect);
        root.addView(connection, margins(0, 14));
        TextView foot = text("只需保持农场服务器在线，无须打开电脑网页监督。跨网络使用时，请连接已部署的 HTTPS 服务。", 12); foot.setTextColor(getColor(R.color.muted)); root.addView(foot);
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
                    else {
                        origin = selected; getPreferences(MODE_PRIVATE).edit().putString("server_origin", origin).apply();
                        if (ServerAddress.cleartext(origin)) Toast.makeText(MainActivity.this, "当前为未加密的 http 连接，口令和设备指令会以明文传输，请只在可信局域网使用", Toast.LENGTH_LONG).show();
                        showFarm();
                    }
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
                Uri target = request.getUrl();
                if (ServerAddress.sameOrigin(origin, target.toString()) && "/mobile/connection-settings".equals(target.getPath())) { showConnection(); return true; }
                boolean allowed = ServerAddress.sameOrigin(origin, target.toString());
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
