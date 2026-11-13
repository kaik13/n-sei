package br.com.bloco.servidor;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

/** Local Android dashboard. Ubuntu and the Bedrock process remain on this phone. */
public final class MainActivity extends Activity {
    private static final int PANEL = 0, WORLD = 1, CONSOLE = 2, SETTINGS = 3;
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
    private LinearLayout page, nav;
    private ScrollView pageScroll, consoleScroll;
    private TextView badge, serverName, status, detail, address, playerMetric, portMetric, versionMetric;
    private TextView ubuntuState, javaState, bedrockState, deviceState, worldState, backupState, consoleText, consoleHint;
    private Button power, javaButton, importWorld, exportWorld, importPack, managePacks, restore, sendCommand, saveSettings;
    private EditText commandInput;
    private int section = PANEL;
    private String localIp, lastConsole = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE);
        base = new File(getFilesDir(), "bloco"); files = new LocalFiles(base); history = new PersistentLog(base);
        if (saved != null) section = Math.max(PANEL, Math.min(SETTINGS, saved.getInt("secao", PANEL)));
        buildShell(); showPage(section);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("secao", section); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); refresh.run(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public void onBackPressed() { if (section != PANEL) showPage(PANEL); else super.onBackPressed(); }

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
        wordmark.addView(text("Bloco Local", 19, TEXT, true)); wordmark.addView(text("SERVIDOR NO CELULAR", 9, MUTED, true));
        header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        badge = text("● Desligado", 11, MUTED, true); badge.setGravity(Gravity.RIGHT); header.addView(badge);
        root.addView(header);
        pageScroll = new ScrollView(this); pageScroll.setFillViewport(true); pageScroll.setClipToPadding(false);
        page = column(); page.setPadding(dp(18), dp(12), dp(18), dp(28)); pageScroll.addView(page);
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = new LinearLayout(this); nav.setPadding(dp(9), dp(7), dp(9), dp(9)); nav.setBackground(shape(SURFACE, BORDER, 0));
        root.addView(nav);
    }
    private void clearPageReferences() {
        serverName = status = detail = address = playerMetric = portMetric = versionMetric = null;
        ubuntuState = javaState = bedrockState = deviceState = worldState = backupState = consoleText = consoleHint = null;
        power = javaButton = importWorld = exportWorld = importPack = managePacks = restore = sendCommand = saveSettings = null;
        commandInput = null; consoleScroll = null; lastConsole = "";
    }
    private void showPage(int which) {
        section = which; page.removeAllViews(); clearPageReferences(); nav.removeAllViews();
        String[] names = {"Painel", "Mundo", "Console", "Ajustes"};
        for (int i = 0; i < names.length; i++) {
            final int destination = i;
            Button item = new Button(this); item.setText(names[i]); item.setAllCaps(false); item.setTextSize(12);
            item.setMinWidth(0); item.setMinimumWidth(0); item.setMinHeight(dp(46));
            item.setPadding(dp(3), 0, dp(3), 0); item.setTextColor(i == which ? INK : MUTED);
            item.setTypeface(Typeface.DEFAULT, i == which ? Typeface.BOLD : Typeface.NORMAL);
            item.setBackground(shape(i == which ? ACCENT : SURFACE, Color.TRANSPARENT, 10));
            item.setContentDescription("Abrir " + names[i]); item.setOnClickListener(v -> showPage(destination));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.setMargins(dp(2), 0, dp(2), 0); nav.addView(item, p);
        }
        if (which == PANEL) buildPanel(); else if (which == WORLD) buildWorld();
        else if (which == CONSOLE) buildConsole(); else buildSettings();
        pageScroll.scrollTo(0, 0); updateUi();
    }
    private TextView metric(LinearLayout row, String label) {
        LinearLayout box = column(); box.addView(text(label, 10, MUTED, true));
        TextView value = text("—", 15, TEXT, true); box.addView(value, gap(4));
        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1)); return value;
    }
    private void buildPanel() {
        title(page, "SEU SERVIDOR", "Tudo em um só lugar.", "O servidor Bedrock roda neste celular. Acompanhe o estado e cuide do seu mundo aqui.");
        LinearLayout hero = card(SURFACE); page.addView(hero, gap(19));
        serverName = text("Meu servidor", 19, TEXT, true); hero.addView(serverName);
        status = text("●  Desligado", 12, MUTED, true); hero.addView(status, gap(7));
        detail = text("Prepare o servidor para começar.", 12, MUTED, false); hero.addView(detail, gap(9));
        power = button("Ligar servidor", true); hero.addView(power, gap(16));
        power.setOnClickListener(v -> {
            AppState.Snapshot current = AppState.snapshot();
            if (current.hosting) {
                if ("Parando".equals(current.status)) return;
                new AlertDialog.Builder(this).setTitle("Desligar o servidor?")
                    .setMessage("O servidor receberá o comando de parada e poderá salvar o mundo antes de fechar.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desligar", (dialog, which) -> stopHosting()).show();
            } else if (!current.fileOperation) startWithConsent();
        });
        LinearLayout row = new LinearLayout(this); row.setPadding(0, dp(17), 0, 0);
        playerMetric = metric(row, "JOGADORES"); portMetric = metric(row, "PORTA"); versionMetric = metric(row, "BEDROCK"); hero.addView(row);

        LinearLayout network = card(SURFACE_ALT); page.addView(network, gap(13));
        network.addView(text("REDE LOCAL", 10, ACCENT, true));
        network.addView(text("Endereço deste celular", 15, TEXT, true), gap(9));
        LinearLayout addressRow = new LinearLayout(this); addressRow.setGravity(Gravity.CENTER_VERTICAL);
        address = text("Buscando rede…", 18, TEXT, true); addressRow.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = button("Copiar", false); addressRow.addView(copy, new LinearLayout.LayoutParams(dp(88), dp(48)));
        copy.setOnClickListener(v -> {
            if (localIp == null) { toast("Conecte o celular a uma rede primeiro."); return; }
            copy("Endereço local", localIp); toast("Endereço local copiado.");
        });
        network.addView(addressRow, gap(7));
        network.addView(text("A sinalização local não confirma que outro jogador conseguiu entrar.", 12, MUTED, false), gap(9));
        Button help = button("Como conectar jogadores", false); network.addView(help, gap(12)); help.setOnClickListener(v -> showNetworkHelp());

        LinearLayout machine = card(SURFACE); page.addView(machine, gap(13));
        machine.addView(text("MÁQUINA LOCAL", 10, ACCENT, true));
        machine.addView(text("Ubuntu e Java", 18, TEXT, true), gap(7));
        machine.addView(text("O Ubuntu é o ambiente do servidor, sem área de trabalho. Java é instalado separadamente; o Bedrock não precisa dele.", 12, MUTED, false), gap(6));
        ubuntuState = text("Ubuntu: aguardando preparo", 12, TEXT, true); machine.addView(ubuntuState, gap(13));
        javaState = text("Java 21: aguardando instalação", 12, TEXT, true); machine.addView(javaState, gap(6));
        bedrockState = text("Bedrock: aguardando instalação", 12, TEXT, true); machine.addView(bedrockState, gap(6));
        javaButton = button("Preparar ou verificar Java", false); machine.addView(javaButton, gap(13)); javaButton.setOnClickListener(v -> prepareJava());
        machine.addView(text("Na primeira instalação, o Java é baixado da internet e verificado por SHA-256. O servidor é baixado da Mojang após sua autorização.", 11, MUTED, false), gap(9));

        LinearLayout device = card(SURFACE); page.addView(device, gap(13));
        device.addView(text("RECURSOS DO CELULAR", 10, ACCENT, true));
        deviceState = text("Verificando memória e armazenamento…", 12, TEXT, false); device.addView(deviceState, gap(8));
        device.addView(text("Mais distância, jogadores ou conteúdos podem consumir mais memória e processamento. O Android pode interromper o serviço.", 11, MUTED, false), gap(9));
        page.addView(text("Bloco Local é independente e não é afiliado à Mojang/Microsoft.", 11, MUTED, false), gap(20));
    }
    private void buildWorld() {
        title(page, "SEUS ARQUIVOS", "Mundo e conteúdos.", "Importe, exporte e organize seu mundo Bedrock. Desligue o servidor antes de alterar arquivos.");
        LinearLayout world = card(SURFACE); page.addView(world, gap(19));
        world.addView(text("Mundo atual", 18, TEXT, true));
        worldState = text("Ainda não há um mundo neste aplicativo.", 12, MUTED, false); world.addView(worldState, gap(7));
        importWorld = button("Importar mundo .mcworld", true); world.addView(importWorld, gap(15));
        importWorld.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            new AlertDialog.Builder(this).setTitle("Importar outro mundo?")
                .setMessage("O servidor precisa estar desligado. Se já houver um mundo, uma cópia anterior será guardada neste aplicativo.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Escolher arquivo", (d, w) -> choose(IMPORT_WORLD)).show();
        });
        exportWorld = button("Exportar cópia do mundo", false); world.addView(exportWorld, gap(9));
        exportWorld.setOnClickListener(v -> { if (canUseFiles()) choose(EXPORT_WORLD); });
        backupState = text("Nenhuma cópia local anterior.", 11, MUTED, false); world.addView(backupState, gap(13));
        restore = button("Restaurar última cópia local", false); world.addView(restore, gap(8));
        restore.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            new AlertDialog.Builder(this).setTitle("Restaurar a última cópia?")
                .setMessage("O mundo atual também será preservado. Exportar uma cópia para fora do aplicativo continua sendo recomendado.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Restaurar", (d, w) -> runFileOperation(() -> files.restoreLatestBackup())).show();
        });
        world.addView(text("Exportar para fora do aplicativo protege a cópia caso ele seja desinstalado.", 11, MUTED, false), gap(10));

        LinearLayout packs = card(SURFACE); page.addView(packs, gap(13));
        packs.addView(text("Add-ons e texturas", 18, TEXT, true));
        packs.addView(text("Arquivos .mcpack e .mcaddon são associados ao mundo atual. Alguns conteúdos protegidos ou incompatíveis podem não funcionar.", 12, MUTED, false), gap(7));
        importPack = button("Adicionar conteúdo", false); packs.addView(importPack, gap(14));
        importPack.setOnClickListener(v -> { if (canUseFiles()) choose(IMPORT_PACK); });
        managePacks = button("Ver e desativar conteúdos", false); packs.addView(managePacks, gap(9));
        managePacks.setOnClickListener(v -> showActivePacks());
        packs.addView(text("Desativar remove a ativação do mundo, mas não apaga os arquivos. Crie uma cópia antes de remover conteúdos de um mundo importante.", 11, MUTED, false), gap(10));
    }
    private void buildConsole() {
        title(page, "LOGS E COMANDOS", "Console ao vivo.", "Acompanhe o servidor e envie comandos Bedrock diretamente pelo aplicativo.");
        LinearLayout panel = card(SURFACE); page.addView(panel, gap(19));
        consoleHint = text("Servidor desligado", 12, MUTED, true); panel.addView(consoleHint);
        consoleScroll = new ScrollView(this); consoleScroll.setFillViewport(true); consoleScroll.setBackground(shape(BG, BORDER, 10));
        consoleText = text("As mensagens aparecerão aqui.", 11, TEXT, false); consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setTextIsSelectable(true); consoleText.setPadding(dp(12), dp(12), dp(12), dp(12)); consoleScroll.addView(consoleText);
        LinearLayout.LayoutParams consoleSize = new LinearLayout.LayoutParams(-1, dp(270)); consoleSize.topMargin = dp(10); panel.addView(consoleScroll, consoleSize);
        commandInput = new EditText(this); commandInput.setSingleLine(true); commandInput.setTextColor(TEXT); commandInput.setHintTextColor(MUTED);
        commandInput.setHint("Ex.: list ou say Olá"); commandInput.setInputType(InputType.TYPE_CLASS_TEXT);
        commandInput.setPadding(dp(12), dp(8), dp(12), dp(8)); commandInput.setBackground(shape(SURFACE_ALT, BORDER, 9)); panel.addView(commandInput, gap(12));
        sendCommand = button("Enviar comando", true); panel.addView(sendCommand, gap(9));
        sendCommand.setOnClickListener(v -> {
            if (!AppState.snapshot().ready) { toast("Aguarde o servidor ficar ativo."); return; }
            final String command;
            try { command = ConsoleCommand.normalize(commandInput.getText().toString()); }
            catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
            Runnable send = () -> {
                try { startService(new Intent(this, HostService.class).setAction(HostService.ACTION_COMMAND).putExtra(HostService.EXTRA_COMMAND, command)); commandInput.setText(""); }
                catch (Exception error) { toast("Não foi possível enviar o comando."); }
            };
            if ("stop".equalsIgnoreCase(command)) new AlertDialog.Builder(this).setTitle("Desligar o servidor?")
                .setMessage("O comando stop encerrará o servidor.").setNegativeButton("Cancelar", null).setPositiveButton("Desligar", (d, w) -> send.run()).show();
            else send.run();
        });
        Button copy = button("Copiar histórico recente", false); panel.addView(copy, gap(9));
        copy.setOnClickListener(v -> {
            try { copy("Logs do Bloco", history.tail(64 * 1024)); toast("Histórico copiado. Ele pode conter nomes de jogadores."); }
            catch (Exception error) { toast("Não foi possível ler o histórico."); }
        });
        Button export = button("Salvar histórico em arquivo", false); panel.addView(export, gap(9));
        export.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Salvar logs?")
            .setMessage("Os logs podem conter nomes de jogadores e outras informações do servidor. Escolha com cuidado onde compartilhar o arquivo.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Escolher local", (d, w) -> choose(EXPORT_LOG)).show());
        panel.addView(text("O histórico fica no armazenamento privado, com tamanho limitado. Ele não é enviado automaticamente para a internet.", 11, MUTED, false), gap(10));
    }
    private EditText input(LinearLayout parent, String label, String value, boolean numeric) {
        parent.addView(text(label, 12, TEXT, true), gap(15));
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(value); field.setTextSize(14);
        field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setPadding(dp(12), dp(7), dp(12), dp(7));
        if (numeric) field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setBackground(shape(SURFACE_ALT, BORDER, 9)); parent.addView(field, gap(6)); return field;
    }
    private Spinner spinner(LinearLayout parent, String label, String[] labels, int selected) {
        parent.addView(text(label, 12, TEXT, true), gap(15));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int position, View convert, ViewGroup container) {
                TextView item = (TextView) super.getView(position, convert, container); item.setTextColor(TEXT); item.setPadding(dp(12), dp(10), dp(12), dp(10)); return item;
            }
            @Override public View getDropDownView(int position, View convert, ViewGroup container) {
                TextView item = (TextView) super.getDropDownView(position, convert, container); item.setTextColor(Color.BLACK); return item;
            }
        };
        spinner.setAdapter(adapter); spinner.setSelection(selected); spinner.setBackground(shape(SURFACE_ALT, BORDER, 9)); parent.addView(spinner, gap(6)); return spinner;
    }
    private CheckBox check(LinearLayout parent, String label, boolean value) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(TEXT); box.setChecked(value); parent.addView(box, gap(10)); return box;
    }
    private int selected(String value, String[] ids) { for (int i = 0; i < ids.length; i++) if (ids[i].equals(value)) return i; return 0; }
    private void buildSettings() {
        title(page, "ADMINISTRAÇÃO", "Ajustes do servidor.", "As alterações são aplicadas na próxima inicialização. Ajuste aos poucos e acompanhe o desempenho real do celular.");
        LinearLayout config = card(SURFACE); page.addView(config, gap(19));
        config.addView(text("Servidor e rede", 18, TEXT, true));
        EditText name = input(config, "Nome do servidor", prefs.getString("nome", "Meu servidor"), false);
        EditText slots = input(config, "Vagas (1 a 30)", Integer.toString(prefs.getInt("slots", 10)), true);
        EditText port = input(config, "Porta TCP e UDP", Integer.toString(NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT))), true);
        config.addView(text("Padrão 19132. Se houver conflito, tente 19142. Use 1024 a 65535, exceto 7551.", 11, MUTED, false), gap(6));
        CheckBox discovery = check(config, "Descoberta automática na rede local", prefs.getBoolean("visibilidade", true));
        String[] modes = {"Sobrevivência", "Criativo", "Aventura"}, modeIds = {"survival", "creative", "adventure"};
        String[] difficulties = {"Pacífico", "Fácil", "Normal", "Difícil"}, difficultyIds = {"peaceful", "easy", "normal", "hard"};
        Spinner mode = spinner(config, "Modo de jogo", modes, selected(prefs.getString("modo", "survival"), modeIds));
        Spinner difficulty = spinner(config, "Dificuldade", difficulties, selected(prefs.getString("dificuldade", "normal"), difficultyIds));
        CheckBox auth = check(config, "Exigir conta Microsoft", prefs.getBoolean("autenticacao", false));
        config.addView(text("Sem autenticação, nomes podem ser falsificados: use apenas em rede confiável e não exponha o servidor à internet. Login Microsoft pode falhar neste ambiente.", 11, MUTED, false), gap(5));

        LinearLayout performance = card(SURFACE); page.addView(performance, gap(13));
        performance.addView(text("Desempenho", 18, TEXT, true));
        performance.addView(text("As opções abaixo são configurações reais do Bedrock; elas não fazem overclock nem garantem velocidade ou 10 jogadores.", 12, MUTED, false), gap(7));
        EditText view = input(performance, "Distância de visão (5 a 32 chunks)", Integer.toString(prefs.getInt("distancia", PerformanceSettings.DEFAULT_VIEW)), true);
        EditText tick = input(performance, "Distância de simulação (4 a 12)", Integer.toString(prefs.getInt("simulacao", PerformanceSettings.DEFAULT_TICK)), true);
        EditText threads = input(performance, "Threads máximas (0 = automático)", Integer.toString(prefs.getInt("threads", PerformanceSettings.DEFAULT_THREADS)), true);
        performance.addView(text("Mais distância aumenta a carga. A simulação não pode superar a visão. Use 0 para deixar o Bedrock usar as threads disponíveis.", 11, MUTED, false), gap(6));
        saveSettings = button("Salvar ajustes", true); performance.addView(saveSettings, gap(16));
        saveSettings.setOnClickListener(v -> {
            if (!canUseFiles()) return;
            String n = name.getText().toString().trim();
            int count = number(slots), portNumber = number(port), viewNumber = number(view), tickNumber = number(tick), threadNumber = number(threads);
            if (n.length() < 2 || n.length() > 40 || n.matches(".*[;\\r\\n\\x00-\\x1f\\x7f].*")) { toast("Use um nome de 2 a 40 caracteres, sem ponto e vírgula."); return; }
            if (count < 1 || count > 30 || !NetworkSettings.validPort(portNumber)) { toast("Verifique as vagas e a porta do servidor."); return; }
            if (!PerformanceSettings.valid(viewNumber, tickNumber, threadNumber)) { toast("Verifique visão, simulação e threads."); return; }
            Runnable save = () -> {
                prefs.edit().putString("nome", n).putInt("slots", count).putInt("porta", portNumber)
                    .putBoolean("visibilidade", discovery.isChecked()).putBoolean("autenticacao", auth.isChecked())
                    .putString("modo", modeIds[mode.getSelectedItemPosition()]).putString("dificuldade", difficultyIds[difficulty.getSelectedItemPosition()])
                    .putInt("distancia", viewNumber).putInt("simulacao", tickNumber).putInt("threads", threadNumber).apply();
                toast("Ajustes salvos para a próxima inicialização."); updateUi();
            };
            if (!auth.isChecked()) new AlertDialog.Builder(this).setTitle("Usar sem autenticação?")
                .setMessage("Qualquer pessoa que alcance o servidor pode falsificar nomes. Use apenas em rede confiável e não abra a porta para a internet.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Usar em rede confiável", (d, w) -> save.run()).show();
            else save.run();
        });
        LinearLayout system = card(SURFACE); page.addView(system, gap(13));
        system.addView(text("Android", 18, TEXT, true));
        Button battery = button("Ajustar economia de bateria", false); system.addView(battery, gap(12));
        battery.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception error) { toast("Abra os ajustes de bateria do Android e permita o Bloco Local em segundo plano."); } });
        Button notification = button("Ajustar notificações", false); system.addView(notification, gap(9));
        notification.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
            catch (Exception error) { toast("Abra as notificações do Bloco Local nos ajustes do Android."); }
        });
        system.addView(text("Mantenha o celular ligado, ventilado e com rede estável. Disponibilidade contínua depende do Android, da energia e da rede.", 11, MUTED, false), gap(10));
    }
    private int number(EditText input) { try { return Integer.parseInt(input.getText().toString()); } catch (Exception ignored) { return -1; } }
    private String version() {
        try { File file = new File(files.store(), "versao.txt"); return file.isFile() ? RuntimeInstaller.readText(file, 128).trim() : ""; }
        catch (Exception ignored) { return ""; }
    }
    private String activeIpv4() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = manager.getActiveNetwork(); LinkProperties props = network == null ? null : manager.getLinkProperties(network);
            if (props != null) for (LinkAddress item : props.getLinkAddresses()) {
                InetAddress ip = item.getAddress(); if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
            }
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress ip : Collections.list(iface.getInetAddresses()))
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return ip.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
    private String deviceResources() {
        try {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
            long available = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
            return String.format(Locale.getDefault(), "Memória disponível no aparelho: %.1f GB\nArmazenamento disponível: %.1f GB",
                memory.availMem / 1_073_741_824.0, available / 1_073_741_824.0);
        } catch (Exception ignored) { return "Não foi possível consultar os recursos do aparelho."; }
    }
    private void updateUi() {
        if (badge == null) return;
        AppState.Snapshot current = AppState.snapshot();
        badge.setText("● " + current.status); badge.setTextColor(current.ready ? ACCENT : "Precisa de atenção".equals(current.status) ? WARN : MUTED);
        boolean available = !current.hosting && !current.fileOperation;
        if (serverName != null) serverName.setText(prefs.getString("nome", "Meu servidor"));
        if (status != null) { status.setText("●  " + (current.ready ? "Ativo · sinalização local" : current.status)); status.setTextColor(current.ready ? ACCENT : MUTED); }
        if (detail != null) detail.setText(current.detail);
        if (power != null) {
            power.setText(current.fileOperation ? "Preparando arquivos…" : current.hosting ? ("Parando".equals(current.status) ? "Desligando…" : "Desligar servidor") : "Ligar servidor");
            power.setEnabled(!current.fileOperation && !"Parando".equals(current.status)); power.setAlpha(power.isEnabled() ? 1f : .55f);
        }
        int port = NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT));
        if (address != null) { localIp = activeIpv4(); address.setText(localIp == null ? "Sem endereço de rede" : localIp); }
        if (playerMetric != null) playerMetric.setText((current.players < 0 ? "—" : Integer.toString(current.players)) + " / " + (current.ready ? current.slots : prefs.getInt("slots", 10)));
        if (portMetric != null) portMetric.setText(Integer.toString(port));
        String installedVersion = version();
        if (versionMetric != null) versionMetric.setText(installedVersion.isEmpty() ? "—" : installedVersion);
        File runtime = new File(base, "runtime");
        if (ubuntuState != null) ubuntuState.setText(new File(runtime, ".bloco-runtime").isFile() ? "Ubuntu: ambiente preparado" : "Ubuntu: será preparado no primeiro início");
        if (javaState != null) javaState.setText(JavaRuntimeInstaller.verified(base, runtime) ? "Java 21 ARM64: instalado e verificado" : JavaRuntimeInstaller.installed(base) ? "Java 21: instalado; execução não confirmada" : "Java 21: aguardando instalação");
        if (bedrockState != null) bedrockState.setText(installedVersion.isEmpty() ? "Bedrock: será baixado após sua autorização" : "Bedrock: " + installedVersion);
        if (javaButton != null) { javaButton.setEnabled(available); javaButton.setAlpha(available ? 1f : .55f); }
        if (deviceState != null) deviceState.setText(deviceResources());
        if (worldState != null) worldState.setText(files.hasWorld() ? "Mundo salvo neste aplicativo." : "Ainda não há um mundo neste aplicativo.");
        if (backupState != null) backupState.setText(files.hasBackup() ? "Existe uma cópia local anterior." : "Nenhuma cópia local anterior.");
        if (importWorld != null) importWorld.setEnabled(available);
        if (exportWorld != null) exportWorld.setEnabled(available && files.hasWorld());
        if (importPack != null) importPack.setEnabled(available && files.hasWorld());
        if (managePacks != null) managePacks.setEnabled(available && files.hasWorld());
        if (restore != null) restore.setEnabled(available && files.hasBackup());
        if (saveSettings != null) saveSettings.setEnabled(available);
        if (consoleHint != null) consoleHint.setText(current.ready ? "Servidor ativo · comandos disponíveis" : "Comandos disponíveis quando o servidor estiver ativo");
        if (sendCommand != null) sendCommand.setEnabled(current.ready);
        if (consoleText != null) updateConsole();
    }
    private void updateConsole() {
        try {
            String lines = history.tail(96 * 1024);
            if (lines.isEmpty()) lines = "As mensagens aparecerão aqui quando você preparar ou iniciar o servidor.";
            if (!lines.equals(lastConsole)) {
                boolean atBottom = lastConsole.isEmpty() || consoleScroll.getChildAt(0).getBottom() - (consoleScroll.getHeight() + consoleScroll.getScrollY()) <= dp(40);
                lastConsole = lines; consoleText.setText(lines);
                ScrollView scroller = consoleScroll;
                if (atBottom) scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
            }
        } catch (Exception ignored) { consoleText.setText("Não foi possível ler o histórico local."); }
    }
    private void prepareJava() {
        if (!canUseFiles()) return;
        runFileOperation(() -> {
            File runtime = RuntimeInstaller.install(base, getAssets().open("runtime.zip"));
            JavaRuntimeInstaller.Progress progress = new JavaRuntimeInstaller.Progress() {
                @Override public void message(String value) {
                    AppState.message(value); try { history.append(value); } catch (IOException ignored) { }
                    runOnUiThread(() -> updateUi());
                }
                @Override public boolean cancelled() { return false; }
            };
            JavaRuntimeInstaller.install(base, progress);
            String result = JavaRuntimeInstaller.verify(base, runtime, progress);
            try { history.append("Java verificado: " + result.replace('\n', ' ')); } catch (IOException ignored) { }
            return "Java 21 ARM64 instalado e verificado no ambiente Ubuntu. O Bedrock funciona independentemente do Java.";
        });
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
                    .setMessage(pack.label() + "\n\nA ativação será removida, mas os arquivos serão preservados. Alguns mundos dependem do conteúdo; exporte uma cópia antes.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Desativar", (x, y) -> runFileOperation(() -> files.disablePack(pack.kind, pack.id))).show();
            }).setNegativeButton("Fechar", null).show();
        } catch (Exception error) { toast(error.getMessage() == null ? "Não foi possível listar os conteúdos." : error.getMessage()); }
    }
    private void startWithConsent() {
        if (prefs.getBoolean("eula", false)) { startWithNetworkWarning(); return; }
        new AlertDialog.Builder(this).setTitle("Servidor oficial Minecraft")
            .setMessage("O aplicativo baixa o servidor oficial diretamente da Mojang e o executa neste aparelho. Ao continuar, você concorda com o Contrato de Usuário Final e a política de privacidade da Microsoft/Mojang.")
            .setNegativeButton("Cancelar", null).setNeutralButton("Ler os termos", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/pt-br/eula"))); } catch (Exception ignored) { }
            }).setPositiveButton("Concordo e continuar", (d, w) -> { prefs.edit().putBoolean("eula", true).apply(); startWithNetworkWarning(); }).show();
    }
    private void startWithNetworkWarning() {
        if (prefs.getBoolean("autenticacao", false)) { startHosting(); return; }
        new AlertDialog.Builder(this).setTitle("Servidor sem autenticação")
            .setMessage("A autenticação Microsoft está desligada. Quem alcançar o servidor pode falsificar nomes de jogadores. Use apenas em rede confiável e não encaminhe a porta para a internet.\n\nVocê pode exigir contas Microsoft nos ajustes, mas isso pode falhar em alguns aparelhos.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Usar em rede confiável", (d, w) -> startHosting()).show();
    }
    private void startHosting() {
        try { startForegroundService(new Intent(this, HostService.class).setAction(HostService.ACTION_START)); }
        catch (Exception error) { new AlertDialog.Builder(this).setTitle("Não foi possível iniciar").setMessage(String.valueOf(error.getMessage())).setPositiveButton("Entendi", null).show(); }
    }
    private void stopHosting() { startService(new Intent(this, HostService.class).setAction(HostService.ACTION_STOP)); }
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
        try { startActivityForResult(intent, kind); } catch (Exception error) { toast("Não foi possível abrir o seletor de arquivos do Android."); }
    }
    private interface FileWork { String run() throws Exception; }
    private void runFileOperation(FileWork work) {
        if (!AppState.beginFileOperation()) { toast("Desligue o servidor e tente novamente."); return; }
        AppState.message("Preparando os arquivos neste celular…"); updateUi();
        new Thread(() -> {
            String result = "", error = null;
            try { result = work.run(); }
            catch (Exception failure) { error = failure.getMessage() == null ? "Não foi possível concluir a operação." : failure.getMessage(); }
            finally { AppState.finishFileOperation(); }
            final String resultText = result, errorText = error;
            AppState.message(errorText == null ? "Operação concluída." : "Confira os detalhes da operação.");
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
        Uri uri = data.getData();
        if (requestCode == IMPORT_WORLD || requestCode == IMPORT_PACK) runFileOperation(() -> {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Não foi possível ler o arquivo escolhido.");
            return requestCode == IMPORT_WORLD ? files.importWorld(input) : files.importPacks(input);
        });
        else if (requestCode == EXPORT_WORLD) runFileOperation(() -> {
            OutputStream output = getContentResolver().openOutputStream(uri, "w");
            if (output == null) throw new IOException("Não foi possível criar o arquivo escolhido.");
            try { files.exportWorld(output); } catch (Exception error) {
                try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                throw error;
            }
            return "Mundo salvo no local escolhido.";
        });
        else if (requestCode == EXPORT_LOG) new Thread(() -> {
            String error = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Não foi possível criar o arquivo.");
                out.write(history.tail(256 * 1024).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) { error = failure.getMessage(); }
            final String result = error;
            runOnUiThread(() -> toast(result == null ? "Histórico salvo no local escolhido." : "Não foi possível salvar: " + result));
        }, "bloco-exportar-log").start();
    }
    private void showNetworkHelp() {
        int port = NetworkSettings.portOrDefault(prefs.getInt("porta", NetworkSettings.DEFAULT_PORT));
        new AlertDialog.Builder(this).setTitle("Como conectar")
            .setMessage("Na mesma rede Wi-Fi, seus amigos podem procurar o mundo ou usar o endereço local mostrado no painel e a porta " + port + ". O Bedrock atual usa TCP para sinalização e UDP para jogar. A resposta local não confirma que um jogador conseguiu entrar.\n\n" +
                "Sem autenticação, use somente rede confiável e não exponha à internet. Para tentar acesso externo com autenticação, é necessário IP público, encaminhamento de TCP e UDP no roteador e, em algumas redes, anunciar um endereço UDP público. O aplicativo não cria IP público nem fornece túnel. CGNAT, NAT, bloqueios ou o aparelho podem impedir a conexão.")
            .setPositiveButton("Entendi", null).show();
    }
    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
