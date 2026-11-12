package br.com.bloco.painel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/** A small Android shell for a user's own HTTPS Bloco panel. */
public final class MainActivity extends Activity {
    private static final int SELECT_UPLOAD = 1001;
    private static final int SAVE_WORLD = 1002;
    private static final int GREEN = Color.rgb(27, 78, 52);
    private static final int INK = Color.rgb(31, 57, 40);
    private static final int MUTED = Color.rgb(87, 110, 91);
    private static final int BACKGROUND = Color.rgb(246, 248, 243);
    private static final String PREFS = "bloco_panel";
    private static final String SAVED_ORIGIN = "origin";

    private LinearLayout root;
    private LinearLayout webContainer;
    private LinearLayout errorBox;
    private ProgressBar progress;
    private WebView webView;
    private ValueCallback<Uri[]> uploadCallback;
    private String panelOrigin;
    private String pendingExportToken;
    private String pendingExportOrigin;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(GREEN);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String saved = getPreferencesStore().getString(SAVED_ORIGIN, "");
        if (validatedOrigin(saved) != null) openPanel(saved);
        else showSetup("");
    }

    private SharedPreferences getPreferencesStore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int background, int border, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) shape.setStroke(dp(1), border);
        return shape;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1.08f);
        return view;
    }

    private LinearLayout.LayoutParams fullWithMargin(int top) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2);
        result.topMargin = dp(top);
        return result;
    }

    private Button action(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setBackground(rounded(primary ? GREEN : Color.WHITE, primary ? Color.TRANSPARENT : Color.rgb(218, 229, 216), 10));
        button.setMinHeight(dp(50));
        return button;
    }

    private void disposeWebView() {
        if (uploadCallback != null) { uploadCallback.onReceiveValue(null); uploadCallback = null; }
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("BlocoAndroid");
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    private void showSetup(String initial) {
        disposeWebView();
        panelOrigin = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(25), dp(42), dp(25), dp(30));
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout branding = new LinearLayout(this);
        branding.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) icon.setImageResource(iconId);
        branding.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout name = new LinearLayout(this);
        name.setOrientation(LinearLayout.VERTICAL);
        name.setPadding(dp(10), 0, 0, 0);
        name.addView(text("bloco.", 25, GREEN, true));
        TextView caption = text("SEU MUNDO, SEU JEITO", 9, MUTED, true);
        name.addView(caption);
        branding.addView(name);
        column.addView(branding);

        TextView kicker = text("SEU SERVIDOR, MAIS PERTO", 10, MUTED, true);
        column.addView(kicker, fullWithMargin(42));
        TextView title = text("Seu mundo começa aqui.", 29, INK, true);
        column.addView(title, fullWithMargin(10));
        TextView description = text("Conecte o painel instalado na sua hospedagem e cuide do servidor pelo celular.", 15, MUTED, false);
        column.addView(description, fullWithMargin(10));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(Color.WHITE, Color.rgb(229, 236, 226), 14));
        column.addView(card, fullWithMargin(25));
        card.addView(text("Endereço do painel", 13, INK, true));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://painel.seudominio.com");
        address.setTextColor(INK);
        address.setHintTextColor(Color.rgb(112, 130, 114));
        address.setTextSize(14);
        address.setPadding(dp(12), dp(6), dp(12), dp(6));
        address.setMinHeight(dp(49));
        address.setBackground(rounded(Color.rgb(250, 252, 249), Color.rgb(218, 229, 216), 9));
        address.setText(initial == null ? "" : initial);
        card.addView(address, fullWithMargin(8));
        TextView tip = text("Use o endereço HTTPS do painel, não o IP do Minecraft.", 12, MUTED, false);
        card.addView(tip, fullWithMargin(7));
        Button connect = action("Conectar ao painel  →", true);
        card.addView(connect, fullWithMargin(19));
        connect.setOnClickListener(v -> {
            String origin = validatedOrigin(address.getText().toString());
            if (origin == null) {
                address.setError("Informe um endereço HTTPS válido, sem caminhos adicionais.");
                address.requestFocus();
                return;
            }
            getPreferencesStore().edit().putString(SAVED_ORIGIN, origin).apply();
            openPanel(origin);
        });

        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setPadding(dp(16), dp(14), dp(16), dp(14));
        notice.setBackground(rounded(Color.rgb(236, 245, 232), Color.rgb(225, 237, 220), 12));
        notice.addView(text("A hospedagem mantém o mundo no ar", 13, GREEN, true));
        notice.addView(text("O app é o controle. Para jogar 24h, configure o painel em uma hospedagem sempre ligada; ela pode ter custos.", 12, MUTED, false), fullWithMargin(6));
        column.addView(notice, fullWithMargin(20));
        column.addView(text("O guia de instalação está no ZIP que acompanha o aplicativo.", 11, MUTED, false), fullWithMargin(19));
    }

    private String validatedOrigin(String value) {
        if (value == null) return null;
        String entered = value.trim();
        if (entered.isEmpty()) return null;
        if (!entered.contains("://")) entered = "https://" + entered;
        try {
            URI uri = new URI(entered);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return null;
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":")) host = "[" + host + "]";
            return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (Exception ignored) { return null; }
    }

    private boolean isPanelUri(Uri uri) {
        if (panelOrigin == null || uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        try {
            URI origin = new URI(panelOrigin);
            int expectedPort = origin.getPort() == -1 ? 443 : origin.getPort();
            int actualPort = uri.getPort() == -1 ? 443 : uri.getPort();
            return uri.getHost() != null && uri.getHost().equalsIgnoreCase(origin.getHost()) && actualPort == expectedPort && uri.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    private void openPanel(String value) {
        String origin = validatedOrigin(value);
        if (origin == null) { showSetup(""); return; }
        disposeWebView();
        panelOrigin = origin;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(4), dp(10), dp(4));
        top.setBackgroundColor(Color.WHITE);
        TextView label = text("bloco.", 21, GREEN, true);
        top.addView(label, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button change = action("Alterar endereço", false);
        change.setTextSize(11);
        change.setMinHeight(dp(38));
        top.addView(change, new LinearLayout.LayoutParams(dp(133), dp(38)));
        change.setOnClickListener(v -> confirmChange());
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        webContainer = new LinearLayout(this);
        webContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NativeBridge(), "BlocoAndroid");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                Intent chooser = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                chooser.addCategory(Intent.CATEGORY_OPENABLE);
                chooser.setType("*/*");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(chooser, SELECT_UPLOAD); return true; }
                catch (ActivityNotFoundException error) {
                    uploadCallback = null;
                    toast("Nenhum aplicativo de arquivos foi encontrado.");
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isPanelUri(uri)) return false;
                String scheme = uri.getScheme();
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException error) { toast("Não foi possível abrir o link."); }
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (errorBox != null) errorBox.setVisibility(View.GONE);
                view.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showConnectionError();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showConnectionError();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showConnectionError();
                toast("Certificado HTTPS inválido. A conexão foi bloqueada.");
            }
        });
        webContainer.addView(webView, new LinearLayout.LayoutParams(-1, -1));
        webView.loadUrl(panelOrigin + "/");
    }

    private void confirmChange() {
        new AlertDialog.Builder(this)
            .setTitle("Alterar endereço?")
            .setMessage("Isso não desliga o servidor. Informe o endereço de outro painel para se conectar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Alterar", (dialog, which) -> {
                String previous = panelOrigin;
                getPreferencesStore().edit().remove(SAVED_ORIGIN).apply();
                showSetup(previous);
            }).show();
    }

    private void showConnectionError() {
        if (webContainer == null || webView == null) return;
        webView.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        if (errorBox == null || errorBox.getParent() != webContainer) {
            errorBox = new LinearLayout(this);
            errorBox.setOrientation(LinearLayout.VERTICAL);
            errorBox.setPadding(dp(24), dp(48), dp(24), dp(24));
            errorBox.addView(text("Não foi possível conectar", 23, INK, true));
            errorBox.addView(text("Confira sua internet, o endereço HTTPS e se o painel está ligado na hospedagem.", 14, MUTED, false), fullWithMargin(10));
            Button retry = action("Tentar novamente", true);
            retry.setOnClickListener(v -> { errorBox.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE); webView.loadUrl(panelOrigin + "/"); });
            errorBox.addView(retry, fullWithMargin(20));
            Button change = action("Alterar endereço", false);
            change.setOnClickListener(v -> confirmChange());
            errorBox.addView(change, fullWithMargin(9));
            webContainer.addView(errorBox, new LinearLayout.LayoutParams(-1, -1));
        }
        errorBox.setVisibility(View.VISIBLE);
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    /** Only the configured HTTPS page can request a download from its own API. */
    private final class NativeBridge {
        @JavascriptInterface public void saveWorld(final String token) {
            if (token == null || token.length() < 16 || token.length() > 4096 || token.contains("\n") || token.contains("\r")) {
                toast("Sua sessão expirou. Entre novamente no painel.");
                return;
            }
            runOnUiThread(() -> {
                if (webView == null || webView.getUrl() == null || !isPanelUri(Uri.parse(webView.getUrl())) || pendingExportToken != null) return;
                pendingExportToken = token;
                pendingExportOrigin = panelOrigin;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "mundo-bloco.mcworld");
                try { startActivityForResult(intent, SAVE_WORLD); }
                catch (ActivityNotFoundException error) {
                    pendingExportToken = null;
                    pendingExportOrigin = null;
                    toast("Nenhum aplicativo de arquivos foi encontrado.");
                }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECT_UPLOAD) {
            if (uploadCallback != null) {
                Uri[] uris = resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{ data.getData() } : null;
                uploadCallback.onReceiveValue(uris);
                uploadCallback = null;
            }
        } else if (requestCode == SAVE_WORLD) {
            String token = pendingExportToken;
            String origin = pendingExportOrigin;
            pendingExportToken = null;
            pendingExportOrigin = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && token != null && origin != null) {
                Uri destination = data.getData();
                new Thread(() -> downloadToDocument(origin, token, destination), "bloco-world-download").start();
            }
        }
    }

    private void downloadToDocument(String origin, String token, Uri destination) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/api/world/download").openConnection();
            connection.setInstanceFollowRedirects(false); // Never forward the panel token to another host.
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Download recusado");
            try (InputStream input = connection.getInputStream(); OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Arquivo indisponível");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            toast("Mundo salvo com sucesso.");
        } catch (Exception error) {
            try { DocumentsContract.deleteDocument(getContentResolver(), destination); } catch (Exception ignored) { }
            toast("Não foi possível salvar. Confira a conexão e pare o servidor antes de baixar.");
        } finally { if (connection != null) connection.disconnect(); }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        disposeWebView();
        super.onDestroy();
    }
}
