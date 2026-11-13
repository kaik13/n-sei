package br.com.bloco.servidor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the actual Bedrock process, not the activity; stops it cleanly on request. */
public final class HostService extends Service {
    static final String ACTION_START = "br.com.bloco.servidor.START";
    static final String ACTION_STOP = "br.com.bloco.servidor.STOP";
    static final String ACTION_COMMAND = "br.com.bloco.servidor.COMMAND";
    static final String EXTRA_COMMAND = "comando";
    static final String PREFS = "bloco_config";
    private static final String CHANNEL = "servidor";
    private static final int NOTIFICATION = 42;
    private static final Pattern PLAYERS = Pattern.compile("There are ([0-9]+)/([0-9]+) players online", Pattern.CASE_INSENSITIVE);
    private volatile boolean stopping;
    private volatile Process process;
    private volatile Thread worker;
    private int latestStartId;
    private final Object inputLock = new Object();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private PersistentLog history;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        history = new PersistentLog(new File(getFilesDir(), "bloco"));
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Servidor Minecraft", NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HostService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Bloco · servidor no celular").setContentText(text).setContentIntent(content)
            .setOngoing(true).addAction(new Notification.Action.Builder(null, "Desligar", stopIntent).build()).build();
    }
    private void notice(String value) {
        AppState.message(value);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION, notification(value));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopping = true;
            if (worker == null) { AppState.finish(null); stopForeground(true); stopSelf(); }
            else AppState.stopping();
            return START_NOT_STICKY;
        }
        if (ACTION_COMMAND.equals(action)) {
            if (!AppState.snapshot().ready || stopping || process == null) record("O console aceita comandos somente com o servidor ativo.");
            else try {
                String command = ConsoleCommand.normalize(intent.getStringExtra(EXTRA_COMMAND));
                record("> " + command);
                if ("stop".equalsIgnoreCase(command)) { stopping = true; AppState.stopping(); }
                else send(command);
            } catch (IllegalArgumentException error) { record(error.getMessage()); }
            if (worker == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (worker != null) return START_NOT_STICKY;
        if (!AppState.beginHosting()) { stopSelf(startId); return START_NOT_STICKY; }
        stopping = false;
        record("── Nova sessão do servidor ──");
        startForeground(NOTIFICATION, notification("Preparando o servidor…"));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bloco:Servidor");
        wakeLock.acquire();
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Bloco:Rede");
            wifiLock.acquire();
        } catch (Exception e) { record("Aviso: não foi possível manter o Wi-Fi ativo: " + e.getMessage()); }
        worker = new Thread(this::runServer, "bloco-host");
        worker.start();
        return START_NOT_STICKY;
    }
    private void record(String value) {
        AppState.line(value);
        try { if (history != null) history.append(value); }
        catch (Exception error) { AppState.line("Histórico de logs indisponível: " + error.getMessage()); }
        // System logs can contain player/server data. Never emit them from
        // release APKs; this is only for explicitly debuggable CI builds.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            android.util.Log.d("BlocoDiag", value);
    }
    private void send(String value) {
        synchronized (inputLock) {
            try {
                Process p = process;
                if (p != null && p.isAlive()) {
                    OutputStream out = p.getOutputStream();
                    out.write((value + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                }
            } catch (Exception e) { record("Console: " + e.getMessage()); }
        }
    }
    private void updateDns(File rootfs) {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = manager.getActiveNetwork();
            LinkProperties props = network == null ? null : manager.getLinkProperties(network);
            if (props == null) return;
            StringBuilder nameservers = new StringBuilder();
            for (InetAddress address : props.getDnsServers()) {
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress())
                    nameservers.append("nameserver ").append(address.getHostAddress().split("%")[0]).append('\n');
            }
            if (nameservers.length() > 0) RuntimeInstaller.writeText(new File(rootfs, "etc/resolv.conf"), nameservers.toString());
        } catch (Exception e) { record("Aviso: DNS do sistema não disponível: " + e.getMessage()); }
    }
    private boolean supportedArchitecture() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }
    private void runServer() {
        String failure = null;
        try {
            if (!supportedArchitecture()) throw new IllegalStateException("Este APK precisa de um celular Android ARM64 de 64 bits.");
            File base = new File(getFilesDir(), "bloco");
            notice("Preparando o ambiente do servidor no celular…");
            File runtime = RuntimeInstaller.install(base, getAssets().open("runtime.zip"));
            if (stopping) return;
            updateDns(new File(runtime, "rootfs"));
            try {
                JavaRuntimeInstaller.Progress javaProgress = new JavaRuntimeInstaller.Progress() {
                    @Override public void message(String text) { notice(text); record(text); }
                    @Override public boolean cancelled() { return stopping; }
                };
                JavaRuntimeInstaller.install(base, javaProgress);
                if (stopping) return;
                String version = JavaRuntimeInstaller.verify(base, runtime, javaProgress);
                record("Java ARM64 verificado no Ubuntu: " + version.replace('\n', ' '));
            } catch (Exception javaError) {
                if (stopping) return;
                record("Aviso: Java não ficou pronto: " + javaError.getMessage() + ". O servidor Bedrock não usa Java e pode continuar; tente novamente no painel.");
            }
            if (stopping) return;
            BedrockInstaller installer = new BedrockInstaller(base);
            File release = installer.prepare(new BedrockInstaller.Progress() {
                @Override public void message(String text) { notice(text); record(text); }
                @Override public boolean cancelled() { return stopping; }
            });
            if (stopping) return;
            SharedPreferences config = getSharedPreferences(PREFS, MODE_PRIVATE);
            int slots = Math.max(1, Math.min(30, config.getInt("slots", 10)));
            int port = NetworkSettings.portOrDefault(config.getInt("porta", NetworkSettings.DEFAULT_PORT));
            int view = PerformanceSettings.view(config.getInt("distancia", PerformanceSettings.DEFAULT_VIEW));
            int tick = PerformanceSettings.tick(config.getInt("simulacao", PerformanceSettings.DEFAULT_TICK), view);
            int threads = PerformanceSettings.threads(config.getInt("threads", PerformanceSettings.DEFAULT_THREADS));
            boolean authenticated = config.getBoolean("autenticacao", false);
            boolean lanVisibility = config.getBoolean("visibilidade", true);
            boolean netherNet = NetworkSettings.netherNet(release.getName());
            installer.configure(config.getString("nome", "Meu servidor"), slots, authenticated,
                config.getString("modo", "survival"), config.getString("dificuldade", "normal"),
                port, lanVisibility, release.getName(), view, tick, threads);
            record("Configuração: visão " + view + ", simulação " + tick + ", threads " + (threads == 0 ? "automáticas" : Integer.toString(threads)) + ".");
            if (stopping) return;
            notice("Iniciando o servidor Bedrock " + release.getName() + "…");
            record("Versão instalada: " + release.getName() + "; transporte " + (netherNet ? "NetherNet" : "RakNet legado") + ".");
            diagnosePorts(port, netherNet, lanVisibility, "antes do início");
            if (stopping) return;
            boolean completed = runProfile(base, runtime, release, slots, port, netherNet, lanVisibility, true);
            if (!completed && !stopping) {
                record("Tentando ambiente alternativo compatível com este aparelho…");
                notice("Tentando ambiente alternativo…");
                completed = runProfile(base, runtime, release, slots, port, netherNet, lanVisibility, false);
            }
            if (!completed && !stopping) {
                diagnosePorts(port, netherNet, lanVisibility, "após a falha");
                throw new IllegalStateException(authenticated
                    ? "Não foi possível iniciar. A autenticação Microsoft pode falhar neste aparelho. Veja os detalhes ou use o modo sem autenticação apenas em rede confiável."
                    : "Não foi possível iniciar neste aparelho. Veja os detalhes para diagnosticar.");
            }
        } catch (Exception e) {
            if (!stopping) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); record("ERRO: " + failure); }
        } finally {
            Process current = process;
            if (current != null && current.isAlive()) current.destroy();
            process = null;
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            final String result = failure;
            new android.os.Handler(getMainLooper()).post(() -> {
                // Complete on the service's main thread so a rapid restart cannot
                // be mistaken for the instance that has just shut down.
                AppState.finish(result);
                worker = null;
                stopForeground(true); stopSelfResult(latestStartId);
            });
        }
    }
    private void diagnosePort(boolean tcp, boolean ipv6, int port) {
        PortDiagnostics.Result result = PortDiagnostics.test(tcp, ipv6, port);
        record("Teste " + (tcp ? "TCP " : "UDP ") + (ipv6 ? "IPv6 " : "IPv4 ") + port + ": " + result.detail + ".");
    }
    private void diagnosePorts(int port, boolean netherNet, boolean lanVisibility, String phase) {
        record("Diagnóstico " + phase + ": testes breves não reservam portas e não garantem que o Bedrock conseguirá abri-las.");
        if (netherNet) { diagnosePort(true, false, port); diagnosePort(true, true, port); }
        diagnosePort(false, false, port);
        if (netherNet) diagnosePort(false, true, port);
        else diagnosePort(false, true, NetworkSettings.legacyIpv6Port(port));
        if (netherNet && lanVisibility) diagnosePort(false, false, NetworkSettings.LAN_DISCOVERY_PORT);
        record("Uma falha pode ser conflito, IPv6 indisponível ou restrição do sistema; confira os detalhes, sem presumir a causa.");
    }
    /** Returns true after a server that reached online later exited cleanly or was stopped. */
    private boolean runProfile(File base, File runtime, File release, int slots, int port,
                               boolean netherNet, boolean lanVisibility, boolean direct) throws Exception {
        ProcessBuilder builder = RuntimeCommand.create(base, runtime, release, direct, netherNet);
        record("Iniciando modo " + (direct ? "direto" : "compatível") + "…");
        final Process child;
        try { child = builder.start(); }
        catch (Exception e) { record("Falha ao executar: " + e.getMessage()); return false; }
        process = child;
        // Both messages matter: BDS can log "Server started" even when its
        // NetherNet listener could not bind. Do not mistake another server's
        // response on that port for this process becoming ready.
        final boolean[] started = { false, false }; // started, listener reported
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    record(line);
                    if (line.contains("Sigfault/Segbus while quitting"))
                        record("Aviso: Box64 relatou uma falha durante a limpeza ao encerrar. Confira o mundo e mantenha cópias exportadas.");
                    synchronized (started) {
                        if (line.contains("Server started.")) started[0] = true;
                        if (netherNet && NetherNetProbe.listenerReported(line, port)) started[1] = true;
                    }
                    Matcher m = PLAYERS.matcher(line);
                    if (m.find()) try { AppState.playerCount(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))); } catch (NumberFormatException ignored) { }
                }
            } catch (Exception e) { record("Leitura do servidor: " + e.getMessage()); }
        }, "bloco-console");
        // A terminated compatibility process may leave a descendant holding
        // its output pipe; that reader must not keep the app process alive.
        reader.setDaemon(true);
        reader.start();
        long startedAt = System.currentTimeMillis(), lastList = 0, stopAt = 0, probeStart = 0;
        boolean verified = false;
        try {
            while (child.isAlive()) {
                boolean ready, listener; synchronized (started) { ready = started[0]; listener = started[1]; }
                long now = System.currentTimeMillis();
                if (stopping) {
                    if (stopAt == 0) { stopAt = now; send("stop"); }
                    if (now - stopAt > 120000) { record("Tempo de parada esgotado; encerrando o processo."); child.destroy(); }
                    if (now - stopAt > 130000) child.destroyForcibly();
                } else if (!ready && now - startedAt > 8 * 60 * 1000L) {
                    record("O servidor demorou demais para iniciar."); child.destroy();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
                } else if (ready && !verified) {
                    if (probeStart == 0) { probeStart = now; notice("Verificando a porta do jogo…"); }
                    NetherNetProbe.Response response = netherNet && listener ? NetherNetProbe.probe(port) : NetherNetProbe.Response.NONE;
                    boolean answered = netherNet
                        ? response == NetherNetProbe.Response.METADATA || (!lanVisibility && response == NetherNetProbe.Response.EMPTY)
                        : RakNetProbe.responds(port);
                    if (answered && child.isAlive()) {
                        verified = true; AppState.running(slots); notice("Servidor ativo neste celular.");
                        record("Porta local " + port + " respondeu ao teste " + (netherNet ? "NetherNet (sinalização TCP)" : "RakNet (UDP)") + ".");
                        if (response == NetherNetProbe.Response.EMPTY)
                            record("A resposta veio sem dados do servidor, como pode ocorrer com descoberta LAN desligada; a identidade do serviço não foi confirmada.");
                        record("O teste local não confirma a entrada de jogadores nem a rede externa.");
                    } else if (now - probeStart > 60000) {
                        record("O servidor não confirmou a sinalização local na porta " + port + (netherNet && !listener ? " (sem confirmação do listener)." : "."));
                        send("stop");
                        if (!child.waitFor(30, TimeUnit.SECONDS)) child.destroyForcibly();
                    }
                } else if (verified && now - lastList > 20000) { send("list"); lastList = now; }
                child.waitFor(1, TimeUnit.SECONDS);
            }
            reader.join(10000);
            int code = child.exitValue();
            record("Processo encerrado (código " + code + ").");
            if (code == 159 && !stopping)
                record("Código 159 pode indicar uma restrição de sistema, mas não identifica sozinho a causa. Confira as linhas anteriores.");
            if (verified && !stopping) throw new IllegalStateException("O servidor parou inesperadamente. Veja os detalhes.");
            return verified || stopping;
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            process = null;
        }
    }
    @Override public void onDestroy() {
        stopping = true;
        send("stop");
        super.onDestroy();
    }
}
