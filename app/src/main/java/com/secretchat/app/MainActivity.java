package com.secretchat.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    static volatile boolean visible = false;
    private WebView web;
    private ValueCallback<Uri[]> chooserCb;
    private static final int PICK = 77;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.setBackgroundColor(0xFF0B1226);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"appassets.androidplatform.net".equals(r.getUrl().getHost());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCb != null) chooserCb.onReceiveValue(null);
                chooserCb = cb;
                try {
                    startActivityForResult(p.createIntent(), PICK);
                } catch (Exception e) {
                    chooserCb = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && chooserCb != null) {
            chooserCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooserCb = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(2);
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    class Bridge {
        @JavascriptInterface
        public void start(String code, String uid) {
            SharedPreferences p = getSharedPreferences("sc", MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit().putString("code", code).putString("uid", uid);
            if (!p.contains("last")) e.putLong("last", System.currentTimeMillis() / 1000);
            e.apply();
            startForegroundService(new Intent(MainActivity.this, ChatService.class));
        }

        @JavascriptInterface
        public void stop() {
            getSharedPreferences("sc", MODE_PRIVATE).edit().clear().apply();
            stopService(new Intent(MainActivity.this, ChatService.class));
        }

        @JavascriptInterface
        public void battery() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }
    }
}
