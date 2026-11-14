package br.com.bloco.servidor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Minimal local dashboard for a Bedrock server hosted on this phone. */
public final class MainActivity extends Activity {
    private static final int IMPORT_WORLD = 100, IMPORT_PACK = 101, EXPORT_WORLD = 102, EXPORT_LOG = 103;
    private static final int BG = Color.rgb(12, 20, 17), SURFACE = Color.rgb(22, 34, 28),
        SURFACE_ALT = Color.rgb(29, 45, 35), BORDER = Color.rgb(47, 65, 52),
        TEXT = Color.rgb(241, 247, 239), MUTED = Color.rgb(165, 185, 168),
        ACCENT = Color.rgb(168, 224, 121), INK = Color.rgb(18, 38, 23),
        WARN = Color.rgb(255, 190, 128);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateUi(); handler.postDelayed(this, 1500); }
    };
    private SharedPreferences prefs;
    private File base;
    private LocalFiles files;
    private PersistentLog history;
    private LinearLayout page;
    private ScrollView pageScroll, logScroll;
    private TextView badge, serverName, detail, address, playersValue, portValue, versionValue, worldSummary, logText, logHint;
    private Button power, copyAddress, filesButton, settingsButton;
    private String localIp, lastLog = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE);
        base = new File(getFilesDir(), "bloco"); files = new LocalFiles(base); history = new PersistentLog(base);
        buildShell(); buildPanel(); updateUi();
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); refresh.run(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int border, float radius) {
        GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) value.setStroke(dp(1), border); return value;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.03f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.topMargin = dp(top); return value;
    }
    private LinearLayout card(int color) {
        LinearLayout value = column(); value.setPadding(dp(17), dp(17), dp(17), dp(17));
        value.setBackground(shape(color, BORDER, 16)); return value;
    }
    private Button button(String label, boolean primary) {
        Button value = new Button(this); value.setText(label); value.setTextSize(14); value.setAllCaps(false);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); value.setMinHeight(dp(48)); value.setMinimumWidth(0);
        value.setTextColor(primary ? INK : TEXT);
        value.setBackground(shape(primary ? ACCENT : SURFACE_ALT, primary ? Color.TRANSPARENT : BORDER, 11));
        return value;
    }
    private void title(LinearLayout parent, String kicker, String heading, String description) {
        parent.addView(text(kicker, 10, ACCENT, true));
        parent.addView(text(heading, 25, TEXT, true), gap(5));
        parent.addView(text(description, 13, MUTED, false), gap(7));
    }
    private void buildShell() {
        LinearLayout root = column(); root.setBackgroundColor(BG); setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(10));
        ImageView icon = new ImageView(this);
        int drawable = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (drawable != 0) icon.setImageResource(drawable);
        icon.setContentDescription("Ícone do Bloco Local"); header.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout wordmark = column(); wordmark.setPadding(dp(10), 0, 0, 0);
        wordmark.addView(text("Bloco Local", 19, TEXT, true)); wordmark.addView(text("SERVIDOR BEDROCK", 9, MUTED, true));
        header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        badge = text("● Desligado", 11, MUTED, true); badge.setGravity(Gravity.RIGHT); header.addView(badge);
        root.addView(header);
        pageScroll = new ScrollView(this); pageScroll.setFillViewport(true); pageScroll.setClipToPadding(false);
        page = column(); page.setPadding(dp(18), dp(12), dp(18), dp(28)); pageScroll.addView(page);
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private TextView metric(LinearLayout row, String label) {
        LinearLayout box = column(); box.addView(text(label, 10, MUTED, true));
        TextView value = text("—", 15, TEXT, true); box.addView(value, gap(4));
        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1)); return value;
    }
    private void buildPanel() {
        title(page, "CONTROLE LOCAL", "Seu servidor.", "Ligue, acompanhe e convide jogadores pela rede local.");

        LinearLayout hero = card(SURFACE); page.addView(hero, gap(17));
        serverName = text("Meu servidor", 19, TEXT, true); hero.addView(serverName);
        detail = text("Prepare o servidor para começar.", 12, MUTED, false); hero.addView(detail, gap(7));
        power = button("Ligar servidor", true); hero.addView(power, gap(14));
        power.setOnClickListener(v -> {
            AppState.Snapshot current = AppState.snapshot();
            if (current.hosting) {
                if ("Parando".equals(current.status)) return;
                new AlertDialog.Builder(this).setTitle("Desligar o servidor?")
                    .setMessage("O servidor receberá o comando de parada e poderá salvar o mundo antes de fechar.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desligar", (dialog, which) -> stopHosting()).show();
            } else if (!current.fileOperation) startWithConsent();
        });
        LinearLayout stats = new LinearLayout(this); stats.setPadding(0, dp(15), 0, 0);
        playersValue = metric(stats, "JOGADORES"); portValue = metric(stats, "PORTA"); versionValue = metric(stats, "BEDROCK"); hero.addView(stats);

        LinearLayout network = card(SURFACE_ALT); page.addView(network, gap(12));
        network.addView(text("ENDEREÇO NA REDE LOCAL", 10, ACCENT, true));
        LinearLayout addressRow = new LinearLayout(this); addressRow.setGravity(Gravity.CENTER_VERTICAL);
        address = text("Buscando Wi-Fi…", 18, TEXT, true); addressRow.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        copyAddress = button("Copiar", false); addressRow.addView(copyAddress, new LinearLayout.LayoutParams(dp(92), dp(48)));
        copyAddress.setOnClickListener(v -> {
            if (localIp == null) { toast("Conecte o celular ao Wi-Fi da rede local."); return; }
            int port = NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT));
            String endpoint = localIp + ":" + port;
            copy("Endereço do servidor", endpoint); toast("Endereço copiado: " + endpoint);
        });
        network.addView(addressRow, gap(8));
        network.addView(text("Use o mesmo Wi-Fi e Minecraft Bedrock compatível. Se o mundo não aparecer, adicione o IP e a porta manualmente.", 12, MUTED, false), gap(7));
        Button help = button("Ajuda para conectar", false); network.addView(help, gap(9)); help.setOnClickListener(v -> showNetworkHelp());

        LinearLayout shortcuts = new LinearLayout(this); shortcuts.setGravity(Gravity.CENTER_VERTICAL); page.addView(shortcuts, gap(12));
        filesButton = button("Mundo e backup", false);
        settingsButton = button("Configurar servidor", false);
        LinearLayout.LayoutParams shortcut = new LinearLayout.LayoutParams(0, dp(52), 1); shortcut.setMargins(0, 0, dp(5), 0);
        shortcuts.addView(filesButton, shortcut);
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(0, dp(52), 1); settingsSize.setMargins(dp(5), 0, 0, 0);
        shortcuts.addView(settingsButton, settingsSize);
        filesButton.setOnClickListener(v -> showWorldManager());
        settingsButton.setOnClickListener(v -> showSettings());
        worldSummary = text("Mundo: —  ·  Backup: —", 11, MUTED, false); page.addView(worldSummary, gap(5));

        LinearLayout logs = card(SURFACE); page.addView(logs, gap(12));
        logs.addView(text("ATIVIDADE E ERROS", 10, ACCENT, true));
        logHint = text("Histórico local · atualizado automaticamente", 11, MUTED, false); logs.addView(logHint, gap(5));
        logScroll = new ScrollView(this); logScroll.setFillViewport(true); logScroll.setBackground(shape(BG, BORDER, 10));
        logText = text("Aguardando mensagens do servidor.", 11, TEXT, false); logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true); logText.setPadding(dp(11), dp(10), dp(11), dp(10)); logScroll.addView(logText);
        LinearLayout.LayoutParams logSize = new LinearLayout.LayoutParams(-1, dp(190)); logSize.topMargin = dp(9); logs.addView(logScroll, logSize);
        LinearLayout logActions = new LinearLayout(this); logActions.setGravity(Gravity.CENTER_VERTICAL); logs.addView(logActions, gap(8));
        Button copyLogs = button("Copiar logs", false), exportLogs = button("Salvar logs", false);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1); half.setMargins(0, 0, dp(4), 0);
        logActions.addView(copyLogs, half);
        LinearLayout.LayoutParams otherHalf = new LinearLayout.LayoutParams(0, dp(48), 1); otherHalf.setMargins(dp(4), 0, 0, 0);
        logActions.addView(exportLogs, otherHalf);
        copyLogs.setOnClickListener(v -> {
            try { copy("Logs do Bloco", history.tail(64 * 1024)); toast("Logs copiados. Podem conter nomes de jogadores."); }
            catch (Exception error) { recordLocal("ERRO: não foi possível copiar os logs: " + message(error)); toast("Não foi possível ler os logs."); }
        });
        exportLogs.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Salvar logs?")
            .setMessage("Os logs podem conter nomes de jogadores e dados do servidor. Escolha com cuidado onde compartilhar o arquivo.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Escolher local", (d, w) -> choose(EXPORT_LOG)).show());
        logs.addView(text("Os logs ficam neste aparelho e não são enviados automaticamente. O histórico é limitado.", 11, MUTED, false), gap(8));
        page.addView(text("Disponibilidade depende do Android, do aparelho e da rede. Bloco Local não é afiliado à Mojang/Microsoft.", 11, MUTED, false), gap(17));
    }
    private EditText input(LinearLayout parent, String label, String value, boolean numeric) {
        parent.addView(text(label, 12, TEXT, true), gap(13));
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(value); field.setTextSize(14);
        field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setPadding(dp(12), dp(7), dp(12), dp(7));
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setBackground(shape(SURFACE_ALT, BORDER, 9)); parent.addView(field, gap(5)); return field;
    }
    private CheckBox check(LinearLayout parent, String label, boolean value) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(TEXT); box.setChecked(value); parent.addView(box, gap(8)); return box;
    }
    private int number(EditText input) { try { return Integer.parseInt(input.getText().toString()); } catch (Exception ignored) { return -1; } }
    private void showSettings() {
        if (!canUseFiles()) return;
        LinearLayout form = column(); form.setPadding(dp(5), dp(5), dp(5), dp(4));
        EditText name = input(form, "Nome do servidor", prefs.getString("nome", "Meu servidor"), false);
        EditText slots = input(form, "Jogadores simultâneos (1 a 5)", Integer.toString(PlayerSettings.clamp(prefs.getInt("slots", PlayerSettings.DEFAULT_SLOTS))), true);
        EditText port = input(form, "Porta TCP e UDP", Integer.toString(NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT))), true);
        CheckBox discovery = check(form, "Descoberta na rede local", prefs.getBoolean("visibilidade", true));
        CheckBox auth = check(form, "Exigir conta Microsoft", prefs.getBoolean("autenticacao", false));
        form.addView(text("Limite máximo: 5 jogadores. Sem autenticação, nomes podem ser falsificados; use apenas uma rede confiável. Login Microsoft pode falhar neste ambiente.", 11, MUTED, false), gap(6));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Configurar servidor").setView(scroll)
            .setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!canUseFiles()) return;
            String server = name.getText().toString().trim();
            int count = number(slots), portNumber = number(port);
            if (server.length() < 2 || server.length() > 40 || server.matches(".*[;\\r\\n\\x00-\\x1f\\x7f].*")) {
                name.setError("Use 2 a 40 caracteres, sem ponto e vírgula."); return;
            }
            if (!PlayerSettings.valid(count) || !NetworkSettings.validPort(portNumber)) {
                toast("Escolha de 1 a 5 jogadores e uma porta válida."); return;
            }
            prefs.edit().putString("nome", server).putInt("slots", count).putInt("porta", portNumber)
                .putBoolean("visibilidade", discovery.isChecked()).putBoolean("autenticacao", auth.isChecked()).apply();
            recordLocal("Configuração salva para a próxima inicialização: " + count + " jogadores, porta " + portNumber + ".");
            updateUi(); dialog.dismiss(); toast("Ajustes salvos para a próxima inicialização.");
        }));
        dialog.show();
    }
    private void showWorldManager() {
        if (!canUseFiles()) return;
        LinearLayout actions = column(); actions.setPadding(dp(5), dp(5), dp(5), dp(4));
        actions.addView(text(worldDescription(), 12, MUTED, false));
        Button importWorld = button("Importar mundo .mcworld", true); actions.addView(importWorld, gap(12));
        Button exportWorld = button("Exportar cópia do mundo", false); actions.addView(exportWorld, gap(7));
        Button restore = button("Restaurar última cópia local", false); actions.addView(restore, gap(7));
        Button importPack = button("Adicionar .mcpack / .mcaddon", false); actions.addView(importPack, gap(13));
        Button managePacks = button("Ver conteúdos ativos", false); actions.addView(managePacks, gap(7));
        exportWorld.setEnabled(files.hasWorld()); restore.setEnabled(files.hasBackup());
        importPack.setEnabled(files.hasWorld()); managePacks.setEnabled(files.hasWorld());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Mundo e backup").setView(actions)
            .setNegativeButton("Fechar", null).create();
        importWorld.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Importar outro mundo?")
                .setMessage("O servidor precisa estar desligado. Se já houver um mundo, uma cópia anterior será guardada neste aplicativo.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Escolher arquivo", (d, w) -> choose(IMPORT_WORLD)).show();
        });
        exportWorld.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            dialog.dismiss(); choose(EXPORT_WORLD);
        });
        restore.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Restaurar a última cópia?")
                .setMessage("O mundo atual também será preservado. Exportar uma cópia para fora do aplicativo continua sendo recomendado.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Restaurar", (d, w) -> runFileOperation("Restaurando o mundo", () -> files.restoreLatestBackup())).show();
        });
        importPack.setOnClickListener(v -> { if (!canUseFiles()) return; dialog.dismiss(); choose(IMPORT_PACK); });
        managePacks.setOnClickListener(v -> { if (!canUseFiles()) return; dialog.dismiss(); showActivePacks(); });
        dialog.show();
    }
    private String worldDescription() {
        return (files.hasWorld() ? "Há um mundo salvo neste aplicativo." : "Ainda não há um mundo neste aplicativo.") + "\n" +
            (files.hasBackup() ? "Existe uma cópia local anterior." : "Nenhuma cópia local anterior.") +
            "\nDesligue o servidor antes de importar, exportar ou restaurar.";
    }
    private void showActivePacks() {
        if (!canUseFiles()) return;
        try {
            List<LocalFiles.ActivePack> packs = files.activePacks();
            if (packs.isEmpty()) { toast("Não há conteúdos ativos listados neste mundo."); return; }
            String[] labels = new String[packs.size()]; for (int i = 0; i < packs.size(); i++) labels[i] = packs.get(i).label();
            new AlertDialog.Builder(this).setTitle("Conteúdos ativos").setItems(labels, (d, i) -> {
                LocalFiles.ActivePack pack = packs.get(i);
                new AlertDialog.Builder(this).setTitle("Desativar conteúdo?")
                    .setMessage(pack.label() + "\n\nA ativação será removida, mas os arquivos serão preservados. Exporte uma cópia antes de alterar um mundo importante.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desativar", (x, y) -> runFileOperation("Desativando conteúdo", () -> files.disablePack(pack.kind, pack.id))).show();
            }).setNegativeButton("Fechar", null).show();
        } catch (Exception error) { reportError("listar conteúdos", error); }
    }
    private String version() {
        try { File file = new File(files.store(), "versao.txt"); return file.isFile() ? RuntimeInstaller.readText(file, 128).trim() : ""; }
        catch (Exception ignored) { return ""; }
    }
    private String ipv4(LinkProperties props) {
        if (props == null) return null;
        for (LinkAddress item : props.getLinkAddresses()) {
            InetAddress ip = item.getAddress();
            if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
        }
        return null;
    }
    private String activeIpv4() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            // Prefer a real Wi-Fi/Ethernet network, not a VPN or mobile route.
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                String address = ipv4(manager.getLinkProperties(network));
                if (address != null) return address;
            }
            // Some OEMs expose a hotspot interface but not a Network object.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = iface.getName().toLowerCase(Locale.ROOT);
                boolean localLink = name.matches("(?:wlan|wifi|ap|swlan|eth|en|p2p)\\d*.*");
                if (!localLink || !iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress ip : Collections.list(iface.getInetAddresses()))
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
    private void updateUi() {
        if (badge == null) return;
        AppState.Snapshot current = AppState.snapshot();
        badge.setText("● " + current.status);
        badge.setTextColor(current.ready ? ACCENT : "Precisa de atenção".equals(current.status) ? WARN : MUTED);
        if (serverName != null) serverName.setText(prefs.getString("nome", "Meu servidor"));
        if (detail != null) {
            detail.setText(current.detail);
            detail.setTextColor("Precisa de atenção".equals(current.status) ? WARN : MUTED);
        }
        boolean available = !current.hosting && !current.fileOperation;
        if (power != null) {
            power.setText(current.fileOperation ? "Preparando arquivos…" : current.hosting ?
                ("Parando".equals(current.status) ? "Desligando…" : "Desligar servidor") : "Ligar servidor");
            power.setEnabled(!current.fileOperation && !"Parando".equals(current.status));
            power.setAlpha(power.isEnabled() ? 1f : .55f);
        }
        if (filesButton != null) { filesButton.setEnabled(available); filesButton.setAlpha(available ? 1f : .55f); }
        if (settingsButton != null) { settingsButton.setEnabled(available); settingsButton.setAlpha(available ? 1f : .55f); }
        int port = NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT));
        if (address != null) { localIp = activeIpv4(); address.setText(localIp == null ? "Sem Wi-Fi" : localIp); }
        if (copyAddress != null) { copyAddress.setEnabled(localIp != null); copyAddress.setAlpha(localIp == null ? .55f : 1f); }
        if (playersValue != null) playersValue.setText((current.players < 0 ? "—" : Integer.toString(current.players)) + " / " +
            (current.ready ? current.slots : PlayerSettings.clamp(prefs.getInt("slots", PlayerSettings.DEFAULT_SLOTS))));
        if (portValue != null) portValue.setText(Integer.toString(port));
        if (versionValue != null) { String installed = version(); versionValue.setText(installed.isEmpty() ? "—" : installed); }
        if (worldSummary != null) worldSummary.setText("Mundo: " + (files.hasWorld() ? "salvo" : "não configurado") +
            "  ·  Backup: " + (files.hasBackup() ? "disponível" : "nenhum"));
        if (logText != null) updateLogs();
    }
    private void updateLogs() {
        try {
            String lines = history.tail(8 * 1024);
            if (lines.isEmpty()) lines = "Aguardando mensagens do servidor.";
            if (!lines.equals(lastLog)) {
                boolean atBottom = lastLog.isEmpty() || logScroll.getChildAt(0).getBottom() -
                    (logScroll.getHeight() + logScroll.getScrollY()) <= dp(36);
                lastLog = lines; logText.setText(lines);
                if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        } catch (Exception error) {
            logHint.setText("Não foi possível ler o histórico local.");
            logText.setText(message(error));
        }
    }
    private interface FileWork { String run() throws Exception; }
    private void runFileOperation(String label, FileWork work) {
        if (!AppState.beginFileOperation()) { toast("Desligue o servidor e tente novamente."); return; }
        AppState.message(label + "…"); recordLocal("Arquivos: " + label + "."); updateUi();
        new Thread(() -> {
            String result = "", error = null;
            try { result = work.run(); }
            catch (Exception failure) { error = message(failure); }
            finally { AppState.finishFileOperation(); }
            final String resultText = result, errorText = error;
            if (errorText == null) recordLocal("Arquivos concluídos: " + resultText);
            else recordLocal("ERRO: " + label + ": " + errorText);
            AppState.message(errorText == null ? "Operação concluída." : "Confira os detalhes nos logs.");
            runOnUiThread(() -> {
                updateUi(); if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle(errorText == null ? "Tudo certo" : "Não foi possível concluir")
                    .setMessage(errorText == null ? resultText : errorText).setPositiveButton("Entendi", null).show();
            });
        }, "bloco-arquivos").start();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); final int kind = requestCode;
        if (kind == IMPORT_WORLD || kind == IMPORT_PACK) runFileOperation(kind == IMPORT_WORLD ? "Importando mundo" : "Importando conteúdo", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível ler o arquivo escolhido.");
                return kind == IMPORT_WORLD ? files.importWorld(input) : files.importPacks(input);
            }
        });
        else if (kind == EXPORT_WORLD) runFileOperation("Exportando mundo", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Não foi possível criar o arquivo escolhido.");
                try { files.exportWorld(output); } catch (Exception error) {
                    try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                    throw error;
                }
            }
            return "Mundo salvo no local escolhido.";
        });
        else if (kind == EXPORT_LOG) new Thread(() -> {
            String error = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Não foi possível criar o arquivo.");
                out.write(history.tail(256 * 1024).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) { error = message(failure); }
            final String problem = error;
            recordLocal(problem == null ? "Histórico de logs exportado." : "ERRO: não foi possível exportar os logs: " + problem);
            runOnUiThread(() -> toast(problem == null ? "Histórico salvo no local escolhido." : "Não foi possível salvar: " + problem));
        }, "bloco-exportar-log").start();
    }
    private void recordLocal(String value) {
        AppState.line(value);
        try { history.append(value); }
        catch (Exception error) { AppState.line("Histórico de logs indisponível: " + message(error)); }
    }
    private void reportError(String action, Exception error) {
        String detail = message(error);
        recordLocal("ERRO: não foi possível " + action + ": " + detail);
        toast("Não foi possível " + action + ". Confira os logs.");
    }
    private String message(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty() ? (error == null ? "erro desconhecido" : error.getClass().getSimpleName()) : value;
    }
    private void startWithConsent() {
        if (prefs.getBoolean("eula", false)) { startWithNetworkWarning(); return; }
        new AlertDialog.Builder(this).setTitle("Servidor oficial Minecraft")
            .setMessage("O aplicativo baixa o servidor oficial diretamente da Mojang e o executa neste aparelho. Ao continuar, você concorda com o Contrato de Usuário Final e a política de privacidade da Microsoft/Mojang.")
            .setNegativeButton("Cancelar", null).setNeutralButton("Ler os termos", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/pt-br/eula"))); }
                catch (Exception error) { recordLocal("ERRO: não foi possível abrir os termos oficiais: " + message(error)); }
            }).setPositiveButton("Concordo e continuar", (d, w) -> { prefs.edit().putBoolean("eula", true).apply(); startWithNetworkWarning(); }).show();
    }
    private void startWithNetworkWarning() {
        if (prefs.getBoolean("autenticacao", false)) { startHosting(); return; }
        new AlertDialog.Builder(this).setTitle("Servidor sem autenticação")
            .setMessage("A autenticação Microsoft está desligada. Quem alcançar o servidor pode falsificar nomes de jogadores. Use apenas em rede confiável e não encaminhe a porta para a internet. Se houver falha, os detalhes ficam nos logs.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Usar em rede confiável", (d, w) -> startHosting()).show();
    }
    private void startHosting() {
        try { startForegroundService(new Intent(this, HostService.class).setAction(HostService.ACTION_START)); }
        catch (Exception error) {
            recordLocal("ERRO: não foi possível solicitar o início do servidor: " + message(error));
            new AlertDialog.Builder(this).setTitle("Não foi possível iniciar").setMessage(message(error) + "\n\nConfira os logs para ver os detalhes.").setPositiveButton("Entendi", null).show();
        }
    }
    private void stopHosting() {
        try { startService(new Intent(this, HostService.class).setAction(HostService.ACTION_STOP)); }
        catch (Exception error) { recordLocal("ERRO: não foi possível solicitar a parada do servidor: " + message(error)); toast("Não foi possível desligar. Confira os logs."); }
    }
    private boolean canUseFiles() {
        AppState.Snapshot current = AppState.snapshot();
        if (current.hosting || current.fileOperation) { toast("Desligue o servidor e aguarde as operações terminarem."); return false; }
        return true;
    }
    private void choose(int kind) {
        boolean export = kind == EXPORT_WORLD || kind == EXPORT_LOG;
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType(kind == EXPORT_LOG ? "text/plain" : kind == EXPORT_WORLD ? "application/zip" : "*/*");
        if (kind == EXPORT_WORLD) intent.putExtra(Intent.EXTRA_TITLE, "mundo-bloco.mcworld");
        if (kind == EXPORT_LOG) intent.putExtra(Intent.EXTRA_TITLE, "logs-bloco.txt");
        try { startActivityForResult(intent, kind); }
        catch (Exception error) { reportError("abrir o seletor de arquivos", error); }
    }
    private void showNetworkHelp() {
        int port = NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT));
        String endpoint = localIp == null ? "Conecte o celular ao Wi-Fi para ver o IP local." : "Endereço: " + localIp + ":" + port + ".";
        new AlertDialog.Builder(this).setTitle("Conectar pela rede local")
            .setMessage("1. Conecte o celular anfitrião e os jogadores à mesma rede Wi-Fi, sem isolamento entre aparelhos.\n" +
                "2. Ligue o servidor e aguarde o estado Ativo. Use Minecraft Bedrock compatível com a versão mostrada.\n" +
                "3. Procure o mundo em Jogar/Amigos. Se não aparecer, adicione manualmente: " + endpoint + "\n" +
                "4. Se houver erro, confira os logs nesta tela. A resposta local e a descoberta LAN não comprovam que outro jogador conseguiu entrar; a causa de Terracotta/no ping depende da rede e ainda não foi confirmada.\n\n" +
                "O limite é de 5 jogadores simultâneos. Não encaminhe portas para a internet. Sem autenticação Microsoft, use apenas uma rede confiável.")
            .setPositiveButton("Entendi", null).show();
    }
    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
