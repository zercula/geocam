package ru.geocamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Оболочка вокруг веб-страницы из assets/index.html. В списке приложений называется «Калькулятор»:
 * страница при запуске показывает рабочий калькулятор, а само приложение (Радуль Лиман) открывается
 * после набора кода и нажатия «=».
 * Даёт странице камеру и GPS, по её запросу кладёт готовые фото в галерею (Pictures/RadulLiman),
 * удаляет эти копии и отправляет фото через системное меню «Поделиться».
 * Сообщает странице, сколько приложение пробыло в фоне, чтобы страница могла снова показать калькулятор.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_PERMS = 1;
    private static final int REQ_FILE = 2;
    private static final int BG = 0xFF14181A;

    private WebView web;
    private boolean pageLoaded = false;
    private long pausedAt = 0;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 33) {
            // В списке недавних приложений не показываем снимок экрана: там была бы камера, а не калькулятор.
            setRecentsScreenshotEnabled(false);
        }

        web = new WebView(this);
        web.setBackgroundColor(BG);
        setContentView(web);
        setupWebView();

        if (hasPermission(Manifest.permission.CAMERA)
                && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            loadPage();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPage() {
        if (!pageLoaded) {
            pageLoaded = true;
            web.loadUrl(START_URL);
        }
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Страница не должна уходить на чужие адреса.
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            wantsVideo = true;
                        }
                    }
                    if (wantsVideo && hasPermission(Manifest.permission.CAMERA)) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, hasPermission(Manifest.permission.ACCESS_FINE_LOCATION), false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            loadPage();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onPause() {
        pausedAt = SystemClock.elapsedRealtime();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (pausedAt != 0) {
            long away = SystemClock.elapsedRealtime() - pausedAt;
            pausedAt = 0;
            web.evaluateJavascript("window.__resume&&window.__resume(" + away + ")", null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Сначала закрываем окна поверх камеры (редактор, галерею), потом выходим.
        web.evaluateJavascript("(window.__back&&window.__back())===true", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    private static String safeName(String name) {
        String n = name == null ? "geo.jpg" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() ? "geo.jpg" : n;
    }

    /** Методы, которые страница вызывает как window.Android.* */
    private class Bridge {

        @JavascriptInterface
        public String savePhoto(String base64, String name) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName(name));
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/RadulLiman");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return "error";
                }
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) {
                        return "error";
                    }
                    os.write(data);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                // Страница запоминает этот адрес, чтобы потом удалить копию из галереи.
                return uri.toString();
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public String deletePhoto(String uriString) {
            try {
                if (uriString == null || !uriString.startsWith("content://media/")) {
                    return "error";
                }
                int removed = getContentResolver().delete(Uri.parse(uriString), null, null);
                return removed > 0 ? "ok" : "error";
            } catch (Exception e) {
                // Файл уже удалён или создан не этим приложением.
                return "error";
            }
        }

        @JavascriptInterface
        public void sharePhoto(String base64, String name) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File file = new File(dir, safeName(name));
                try (FileOutputStream os = new FileOutputStream(file)) {
                    os.write(data);
                }
                final Uri uri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", file);
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newRawUri("", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Отправить фото"));
                });
            } catch (Exception ignored) {
                // Отправка не удалась: страница ничего не покажет, пользователь может нажать «Сохранить фото».
            }
        }
    }
}
