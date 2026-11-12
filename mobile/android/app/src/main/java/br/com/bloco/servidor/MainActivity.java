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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Single-device control surface: all administration stays local to the phone. */
public final class MainActivity extends Activity {
    private static final int IMPORT_WORLD = 100, IMPORT_PACK = 101, EXPORT_WORLD = 102;
    private static final int GREEN = Color.rgb(28, 79, 53), DARK = Color.rgb(37, 63, 44),
        MUTED = Color.rgb(88, 111, 91), BG = Color.rgb(246, 248, 243), BORDER = Color.rgb(224, 232, 222);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serverTitle, status, detail, address, players, version, worldState, backupState;
    private Button power, worldImport, worldExport, packImport, settings, restore;
    private SharedPreferences prefs;
    private LocalFiles files;
    private String localIp;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateUi(); handler.postDelayed(this, 1500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(GREEN); getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE);
        files = new LocalFiles(new File(getFilesDir(), "bloco"));
        buildUi();
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); refresh.run(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int border, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) d.setStroke(dp(1), border); return d;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(2), 1.05f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p;
    }
    private LinearLayout card(int color) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18)); box.setBackground(shape(color, BORDER, 14)); return box;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(dp(49));
        b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackground(shape(primary ? GREEN : Color.WHITE, primary ? Color.TRANSPARENT : BORDER, 9));
        return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(24), dp(20), dp(34));
        scroll.addView(root); setContentView(scroll);

        LinearLayout branding = new LinearLayout(this); branding.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        int icon = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()); if (icon != 0) logo.setImageResource(icon);
        branding.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout wordmark = new LinearLayout(this); wordmark.setOrientation(LinearLayout.VERTICAL); wordmark.setPadding(dp(10), 0, 0, 0);
        wordmark.addView(text("bloco.", 24, GREEN, true)); wordmark.addView(text("SERVIDOR NO CELULAR", 9, MUTED, true));
        branding.addView(wordmark); root.addView(branding);

        root.addView(text("SEU MUNDO, NA SUA MÃO", 10, MUTED, true), gap(28));
        root.addView(text("Seu celular é o servidor.", 29, DARK, true), gap(7));
        root.addView(text("Crie e cuide do seu mundo Bedrock aqui mesmo, sem instalar outro aplicativo de hospedagem.", 14, MUTED, false), gap(8));

        LinearLayout hero = card(Color.WHITE); root.addView(hero, gap(22));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this); identity.setOrientation(LinearLayout.VERTICAL);
        serverTitle = text("Meu servidor", 19, DARK, true); identity.addView(serverTitle);
        status = text("●  Desligado", 12, MUTED, true); identity.addView(status, gap(6));
        row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("BEDROCK", 9, GREEN, true); badge.setPadding(dp(8), dp(6), dp(8), dp(6)); badge.setBackground(shape(Color.rgb(238, 246, 233), Color.TRANSPARENT, 6)); row.addView(badge);
        hero.addView(row);
        detail = text("Seu celular será o servidor.", 12, MUTED, false); hero.addView(detail, gap(13));
        power = button("Ligar servidor  →", true); hero.addView(power, gap(18));
        power.setOnClickListener(v -> {
            AppState.Snapshot snapshot = AppState.snapshot();
            if (snapshot.hosting) {
                if (snapshot.status.equals("Parando")) return;
                new AlertDialog.Builder(this).setTitle("Desligar o servidor?")
                    .setMessage("O mundo será salvo antes de o servidor parar.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desligar", (d, w) -> stopHosting()).show();
            } else if (!snapshot.fileOperation) startWithConsent();
        });
        Button logs = button("Ver detalhes e console", false); hero.addView(logs, gap(8)); logs.setOnClickListener(v -> showLogs());

        LinearLayout network = card(Color.rgb(238, 246, 234)); root.addView(network, gap(13));
        network.addView(text("CONECTAR AO MUNDO", 10, GREEN, true));
        network.addView(text("Endereço local do celular", 14, DARK, true), gap(10));
        LinearLayout addrRow = new LinearLayout(this); addrRow.setGravity(Gravity.CENTER_VERTICAL);
        address = text("Procurando rede…", 19, DARK, true); addrRow.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = button("Copiar", false); addrRow.addView(copy, new LinearLayout.LayoutParams(dp(88), dp(46))); network.addView(addrRow, gap(6));
        copy.setOnClickListener(v -> {
            if (localIp == null) { toast("Conecte o celular a uma rede primeiro."); return; }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Endereço do servidor", localIp)); toast("Endereço local copiado.");
        });
        network.addView(text("Porta do jogo: 19132 (UDP)", 12, GREEN, true), gap(8));
        network.addView(text("Na mesma rede Wi-Fi, seus amigos usam este endereço. Na internet, é necessário um IPv4 público e configurar o roteador.", 12, MUTED, false), gap(9));
        Button networkHelp = button("Como jogar pela internet?", false); network.addView(networkHelp, gap(12)); networkHelp.setOnClickListener(v -> showNetworkHelp());

        root.addView(text("SEU MUNDO", 10, MUTED, true), gap(26));
        LinearLayout worldCard = card(Color.WHITE); root.addView(worldCard, gap(9));
        worldCard.addView(text("Mundo Bedrock", 18, DARK, true));
        worldState = text("Crie ou importe um mundo.", 12, MUTED, false); worldCard.addView(worldState, gap(6));
        worldImport = button("Importar mundo .mcworld", false); worldCard.addView(worldImport, gap(14));
        worldImport.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            new AlertDialog.Builder(this).setTitle("Importar outro mundo?")
                .setMessage("O servidor precisa estar desligado. Se já existir um mundo, uma cópia será preservada no aplicativo.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Escolher arquivo", (d, w) -> choose(IMPORT_WORLD)).show();
        });
        worldExport = button("Salvar cópia do mundo", false); worldCard.addView(worldExport, gap(8));
        worldExport.setOnClickListener(v -> { if (canUseFiles()) choose(EXPORT_WORLD); });
        backupState = text("Cópias locais: nenhuma ainda", 11, MUTED, false); worldCard.addView(backupState, gap(12));
        restore = button("Restaurar última cópia", false); worldCard.addView(restore, gap(8));
        restore.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            new AlertDialog.Builder(this).setTitle("Restaurar a última cópia?")
                .setMessage("O mundo atual também será preservado. O servidor precisa estar desligado.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Restaurar", (d, w) -> runFileOperation(() -> files.restoreLatestBackup())).show();
        });

        LinearLayout packs = card(Color.WHITE); root.addView(packs, gap(13));
        packs.addView(text("Add-ons e texturas", 18, DARK, true));
        packs.addView(text("Adicione arquivos .mcpack ou .mcaddon. Crie ou importe um mundo primeiro e mantenha o servidor desligado.", 12, MUTED, false), gap(7));
        packImport = button("Adicionar conteúdo", false); packs.addView(packImport, gap(14));
        packImport.setOnClickListener(v -> { if (canUseFiles()) choose(IMPORT_PACK); });

        root.addView(text("SEU SERVIDOR", 10, MUTED, true), gap(26));
        LinearLayout config = card(Color.WHITE); root.addView(config, gap(9));
        players = text("Jogadores: — / 10", 13, DARK, true); config.addView(players);
        version = text("Versão: ainda não instalada", 12, MUTED, false); config.addView(version, gap(8));
        settings = button("Configurações do servidor", false); config.addView(settings, gap(14)); settings.setOnClickListener(v -> showSettings());
        Button battery = button("Ajustar economia de bateria", false); config.addView(battery, gap(8));
        battery.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { toast("Abra os ajustes de bateria do Android e permita o Bloco em segundo plano."); } });
        config.addView(text("Mantenha o celular ligado, conectado e de preferência na tomada. O Android pode interromper o aplicativo; funcionamento 24h não é garantido.", 12, MUTED, false), gap(14));
        root.addView(text("Feito para Bedrock · Bloco 2.0.0\nO Bloco não é afiliado à Mojang ou Microsoft.", 11, MUTED, false), gap(24));
    }
    private String activeIpv4() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = manager.getActiveNetwork(); LinkProperties p = network == null ? null : manager.getLinkProperties(network);
            if (p != null) for (LinkAddress a : p.getLinkAddresses()) {
                InetAddress ip = a.getAddress(); if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
            }
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress ip : Collections.list(iface.getInetAddresses()))
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
    private void updateUi() {
        if (power == null) return;
        AppState.Snapshot s = AppState.snapshot();
        serverTitle.setText(prefs.getString("nome", "Meu servidor")); status.setText("●  " + s.status);
        status.setTextColor(s.ready ? Color.rgb(52, 128, 66) : s.status.equals("Precisa de atenção") ? Color.rgb(171, 76, 59) : MUTED);
        detail.setText(s.fileOperation ? "Preparando o arquivo escolhido…" : s.detail);
        power.setText(s.hosting ? (s.status.equals("Parando") ? "Desligando…" : "Desligar servidor") : "Ligar servidor  →");
        power.setEnabled(!s.fileOperation && !s.status.equals("Parando"));
        localIp = activeIpv4(); address.setText(localIp == null ? "Sem endereço de rede" : localIp);
        players.setText("Jogadores: " + (s.players < 0 ? "—" : s.players) + " / " + (s.ready ? s.slots : prefs.getInt("slots", 10)));
        File marker = new File(files.store(), "versao.txt");
        String installed = "ainda não instalada";
        try { if (marker.isFile()) installed = RuntimeInstaller.readText(marker, 128).trim(); } catch (Exception ignored) { }
        version.setText("Versão Bedrock: " + installed);
        worldState.setText(files.hasWorld() ? "Mundo salvo neste celular." : "Crie ou importe um mundo.");
        boolean available = !s.hosting && !s.fileOperation;
        worldImport.setEnabled(available); worldExport.setEnabled(available && files.hasWorld());
        packImport.setEnabled(available && files.hasWorld()); settings.setEnabled(available); restore.setEnabled(available && files.hasBackup());
        backupState.setText(files.hasBackup() ? "Existe uma cópia local anterior." : "Cópias locais: nenhuma ainda.");
    }
    private void startWithConsent() {
        if (prefs.getBoolean("eula", false)) { startHosting(); return; }
        new AlertDialog.Builder(this).setTitle("Servidor oficial Minecraft")
            .setMessage("O Bloco baixa o servidor oficial diretamente da Mojang e o executa neste aparelho. Ao continuar, você concorda com o Contrato de Usuário Final e a política de privacidade da Microsoft/Mojang.")
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Ler os termos", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/pt-br/eula"))); } catch (Exception ignored) { } })
            .setPositiveButton("Concordo e iniciar", (d, w) -> { prefs.edit().putBoolean("eula", true).apply(); startHosting(); }).show();
    }
    private void startHosting() {
        try { startForegroundService(new Intent(this, HostService.class).setAction(HostService.ACTION_START)); }
        catch (Exception e) { new AlertDialog.Builder(this).setTitle("Não foi possível iniciar").setMessage(e.getMessage()).setPositiveButton("Entendi", null).show(); }
    }
    private void stopHosting() { startService(new Intent(this, HostService.class).setAction(HostService.ACTION_STOP)); }
    private boolean canUseFiles() {
        AppState.Snapshot s = AppState.snapshot();
        if (s.hosting || s.fileOperation) { toast("Desligue o servidor e aguarde as operações terminarem."); return false; }
        return true;
    }
    private void choose(int kind) {
        Intent intent = new Intent(kind == EXPORT_WORLD ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType(kind == EXPORT_WORLD ? "application/zip" : "*/*");
        if (kind == EXPORT_WORLD) intent.putExtra(Intent.EXTRA_TITLE, "mundo-bloco.mcworld");
        try { startActivityForResult(intent, kind); } catch (Exception e) { toast("Não foi possível abrir o seletor de arquivos do Android."); }
    }
    private interface FileWork { String run() throws Exception; }
    private void runFileOperation(FileWork work) {
        if (!AppState.beginFileOperation()) { toast("Desligue o servidor e tente novamente."); return; }
        updateUi();
        new Thread(() -> {
            String result = "", error = null;
            try { result = work.run(); }
            catch (Exception e) { error = e.getMessage() == null ? "Não foi possível concluir a operação." : e.getMessage(); }
            finally { AppState.finishFileOperation(); }
            final String resultText = result, errorText = error;
            runOnUiThread(() -> {
                updateUi();
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle(errorText == null ? "Tudo certo" : "Não foi possível concluir")
                    .setMessage(errorText == null ? resultText : errorText).setPositiveButton("Entendi", null).show();
            });
        }, "bloco-arquivos").start();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT_WORLD || requestCode == IMPORT_PACK) runFileOperation(() -> {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Não foi possível ler o arquivo escolhido.");
            return requestCode == IMPORT_WORLD ? files.importWorld(in) : files.importPacks(in);
        });
        else if (requestCode == EXPORT_WORLD) runFileOperation(() -> {
            OutputStream out = getContentResolver().openOutputStream(uri, "w");
            if (out == null) throw new IOException("Não foi possível criar o arquivo escolhido.");
            try { files.exportWorld(out); } catch (Exception e) {
                try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                throw e;
            }
            return "Mundo salvo no local escolhido.";
        });
    }
    private void showLogs() {
        AppState.Snapshot snapshot = AppState.snapshot();
        StringBuilder message = new StringBuilder();
        for (String line : snapshot.logs) message.append(line).append('\n');
        if (message.length() == 0) message.append("Os detalhes aparecerão aqui quando o servidor iniciar.");
        ScrollView scroll = new ScrollView(this); TextView text = text(message.toString(), 11, DARK, false);
        text.setTextIsSelectable(true); text.setPadding(dp(20), dp(10), dp(20), dp(15)); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Detalhes do servidor").setView(scroll).setPositiveButton("Fechar", null).show();
    }
    private void showNetworkHelp() {
        new AlertDialog.Builder(this).setTitle("Jogar fora do Wi-Fi")
            .setMessage("O aplicativo não cria um IP público: o endereço pertence à sua rede ou operadora.\n\nNa mesma rede, use o endereço local mostrado no Bloco e a porta UDP 19132.\n\nPara amigos em outra rede, o roteador precisa ter IPv4 público e encaminhar a porta UDP 19132 para o IP local do celular. Seus amigos usam o IPv4 público do roteador e a porta 19132.\n\nSe a operadora usa CGNAT ou bloqueia conexões de entrada, não é possível receber jogadores externos sem mudar a rede ou usar um serviço externo. O Bloco não usa túnel nem hospedagem externa.")
            .setPositiveButton("Entendi", null).show();
    }
    private void showSettings() {
        if (!canUseFiles()) return;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(24), dp(6), dp(24), dp(8));
        box.addView(text("Nome do servidor", 13, DARK, true));
        EditText name = new EditText(this); name.setSingleLine(); name.setText(prefs.getString("nome", "Meu servidor")); box.addView(name, gap(4));
        box.addView(text("Vagas (10 a 30)", 13, DARK, true), gap(12));
        EditText slots = new EditText(this); slots.setSingleLine(); slots.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); slots.setText(Integer.toString(prefs.getInt("slots", 10))); box.addView(slots, gap(4));
        box.addView(text("Modo de jogo", 13, DARK, true), gap(12));
        Spinner mode = new Spinner(this); String[] modes = {"Sobrevivência", "Criativo", "Aventura"}, modeIds = {"survival", "creative", "adventure"};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        for (int i = 0; i < modeIds.length; i++) if (modeIds[i].equals(prefs.getString("modo", "survival"))) mode.setSelection(i);
        box.addView(mode, gap(5));
        box.addView(text("Dificuldade", 13, DARK, true), gap(12));
        Spinner difficulty = new Spinner(this); String[] labels = {"Pacífico", "Fácil", "Normal", "Difícil"}, ids = {"peaceful", "easy", "normal", "hard"};
        difficulty.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(prefs.getString("dificuldade", "normal"))) difficulty.setSelection(i);
        box.addView(difficulty, gap(5));
        CheckBox auth = new CheckBox(this); auth.setText("Exigir conta Microsoft dos jogadores"); auth.setTextColor(DARK);
        auth.setChecked(prefs.getBoolean("autenticacao", true)); box.addView(auth, gap(12));
        box.addView(text("Desativar a autenticação permite falsificar nomes. Use essa opção somente em redes de confiança.", 11, MUTED, false), gap(3));
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Configurações").setView(scroll).setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(); int count;
            try { count = Integer.parseInt(slots.getText().toString()); } catch (NumberFormatException e) { count = 0; }
            if (n.length() < 2 || n.length() > 40 || n.matches(".*[\\r\\n].*") || count < 10 || count > 30) {
                toast("Informe um nome de 2 a 40 caracteres e de 10 a 30 vagas."); return;
            }
            if (!auth.isChecked()) {
                final int selectedCount = count;
                new AlertDialog.Builder(this).setTitle("Desativar autenticação?")
                    .setMessage("Qualquer jogador poderá usar o nome de outra pessoa. Não exponha o servidor à internet nesse modo.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Usar só em rede confiável", (x, y) -> {
                        saveSettings(n, selectedCount, false, modeIds[mode.getSelectedItemPosition()], ids[difficulty.getSelectedItemPosition()]); dialog.dismiss();
                    }).show();
            } else { saveSettings(n, count, true, modeIds[mode.getSelectedItemPosition()], ids[difficulty.getSelectedItemPosition()]); dialog.dismiss(); }
        }));
        dialog.show();
    }
    private void saveSettings(String name, int slots, boolean auth, String mode, String difficulty) {
        prefs.edit().putString("nome", name).putInt("slots", slots).putBoolean("autenticacao", auth)
            .putString("modo", mode).putString("dificuldade", difficulty).apply(); updateUi();
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
