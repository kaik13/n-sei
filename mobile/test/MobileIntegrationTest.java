package br.com.bloco.servidor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the genuine downloaded Bedrock executable through both bundled ARM64 profiles. */
public final class MobileIntegrationTest {
    private static void send(Process p, String command) throws Exception {
        p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8)); p.getOutputStream().flush();
    }
    private static Process launch(File base, File runtime, File release, boolean direct, boolean filtered) throws Exception {
        ProcessBuilder builder = RuntimeCommand.create(base, runtime, release, direct);
        if (filtered) {
            File helper = new File("mobile/test/seccomp-exec.py").getAbsoluteFile();
            if (!helper.isFile()) throw new Exception("Teste de filtro ARM64 ausente");
            List<String> command = new ArrayList<>();
            command.add("python3"); command.add(helper.toString()); command.addAll(builder.command());
            builder.command(command);
        }
        return builder.start();
    }
    private static void cycle(File base, File runtime, File release, boolean direct, boolean filtered, int port) throws Exception {
        boolean netherNet = NetworkSettings.netherNet(release.getName());
        String mode = (direct ? "direct" : "proot") + (filtered ? " + filtro simulado" : "");
        System.out.println("Testing " + mode + " on real ARM64 Linux, port " + port);
        Process p = launch(base, runtime, release, direct, filtered);
        AtomicBoolean started = new AtomicBoolean(), listener = new AtomicBoolean(), listed = new AtomicBoolean();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(mode + " | " + line);
                    synchronized (tail) { tail.append(line).append('\n'); if (tail.length() > 10000) tail.delete(0, tail.length() - 10000); }
                    if (line.contains("Server started.")) started.set(true);
                    if (NetherNetProbe.listenerReported(line, port)) listener.set(true);
                    if (line.contains("players online")) listed.set(true);
                }
            } catch (Exception ignored) { }
        });
        // A failed PRoot child can leave a descendant holding the log pipe.
        reader.setDaemon(true);
        reader.start();
        try {
            long deadline = System.currentTimeMillis() + 3 * 60 * 1000L;
            while (!started.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            if (!started.get()) { synchronized (tail) {
                throw new Exception(mode + " não iniciou:\n" + tail.substring(Math.max(0, tail.length() - 2800)));
            } }
            deadline = System.currentTimeMillis() + 90000; boolean answered = false;
            while (!answered && p.isAlive() && System.currentTimeMillis() < deadline) {
                answered = netherNet ? listener.get() && NetherNetProbe.responds(port) : RakNetProbe.responds(port);
                if (!answered) Thread.sleep(250);
            }
            if (!answered) { synchronized (tail) {
                throw new Exception(mode + " não confirmou a sinalização na porta " + port + ":\n" + tail.substring(Math.max(0, tail.length() - 2800)));
            } }
            send(p, "list"); deadline = System.currentTimeMillis() + 60000;
            while (!listed.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            if (!listed.get()) throw new Exception(mode + " não respondeu ao comando list");
            send(p, "stop");
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new Exception(mode + " não encerrou corretamente");
            reader.join(10000);
            System.out.println("PASS: " + mode + " — inicialização, " + (netherNet ? "sinalização NetherNet TCP" : "RakNet UDP") + ", console e parada limpa");
        } finally { if (p.isAlive()) p.destroyForcibly(); }
    }
    private static void blockedPort(File base, File runtime, File release, int port) throws Exception {
        Process p = launch(base, runtime, release, false, false);
        AtomicBoolean started = new AtomicBoolean(), openedWorld = new AtomicBoolean(), listener = new AtomicBoolean();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("occupied | " + line);
                    synchronized (tail) { tail.append(line).append('\n'); if (tail.length() > 10000) tail.delete(0, tail.length() - 10000); }
                    if (line.contains("Opening level")) openedWorld.set(true);
                    if (line.contains("Server started.")) started.set(true);
                    if (NetherNetProbe.listenerReported(line, port)) listener.set(true);
                }
            } catch (Exception ignored) { }
        });
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.currentTimeMillis() + 60000;
            while (!started.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            reader.join(p.isAlive() ? 500 : 10000);
            if (!openedWorld.get()) { synchronized (tail) {
                throw new Exception("Teste de conflito não chegou ao mundo:\n" + tail.substring(Math.max(0, tail.length() - 2800)));
            } }
            // The blocker deliberately answers a valid-looking Bedrock status.
            // A probe alone would falsely mark this process online; it must also
            // report its own listener and successful start.
            if (!NetherNetProbe.responds(port)) throw new Exception("O servidor simulado não respondeu na porta ocupada");
            if (started.get() && listener.get()) throw new Exception("Outro servidor na porta ocupada passou pela verificação de prontidão");
            System.out.println("PASS: outro servidor em TCP " + port + " não foi confundido com o Bedrock iniciado");
        } finally {
            if (p.isAlive()) {
                try { send(p, "stop"); } catch (Exception ignored) { }
                if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
            }
            reader.join(10000);
        }
    }
    public static void main(String[] args) throws Exception {
        File asset = new File(args[0]), base = new File(args[1]);
        File runtime = RuntimeInstaller.install(base, new FileInputStream(asset));
        System.out.println("PASS: runtime asset extracted with Android's installer logic");
        // Mirror the Android service's active-network DNS setup on the CI host.
        java.nio.file.Path hostDns = new File("/etc/resolv.conf").toPath();
        if (java.nio.file.Files.isRegularFile(hostDns))
            java.nio.file.Files.copy(hostDns, new File(runtime, "rootfs/etc/resolv.conf").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        BedrockInstaller installer = new BedrockInstaller(base);
        File release = installer.prepare(new BedrockInstaller.Progress() {
            @Override public void message(String text) { System.out.println(text); }
            @Override public boolean cancelled() { return false; }
        });
        // Deliberately offline for an isolated LAN protocol test. The app
        // requires explicit confirmation before using this mode.
        int port = NetworkSettings.DEFAULT_PORT;
        installer.configure("Bloco CI", 10, false, "survival", "normal", port, true, release.getName());
        cycle(base, runtime, release, true, false, port);
        LocalFiles files = new LocalFiles(base);
        if (!files.hasWorld()) throw new Exception("Mundo não foi criado");
        File marker = new File(files.world(), "bloco-test-marker"); RuntimeInstaller.writeText(marker, "persistente");
        RuntimeInstaller.writeText(new File(runtime, ".bloco-runtime"), "ubuntu24-box64-v044-proot-v550-3\n");
        runtime = RuntimeInstaller.install(base, new FileInputStream(asset));
        if (!marker.isFile() || !files.hasWorld()) throw new Exception("Atualização do runtime alterou o mundo");
        cycle(base, runtime, release, false, false, port);
        if (NetworkSettings.netherNet(release.getName())) {
            // An actual occupied TCP port is different from assuming the user's
            // phone has a conflict. Test recovery on a separate chosen port.
            try (ServerSocket blocker = new ServerSocket()) {
                blocker.setReuseAddress(false); blocker.bind(new InetSocketAddress("0.0.0.0", port));
                Thread otherServer = new Thread(() -> {
                    String json = "{\"name\":\"Outro servidor\",\"protocol\":2225,\"version\":\"1.26.63\",\"level\":\"Outro mundo\",\"players\":0,\"maxPlayers\":10}";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    byte[] header = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    while (!blocker.isClosed()) try (java.net.Socket connection = blocker.accept()) {
                        connection.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                        String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        connection.getOutputStream().write(header); connection.getOutputStream().write(body); connection.getOutputStream().flush();
                    } catch (Exception ignored) { }
                });
                otherServer.setDaemon(true); otherServer.start();
                if (PortDiagnostics.test(true, false, port).available) throw new Exception("Diagnóstico não detectou conflito TCP");
                blockedPort(base, runtime, release, port);
                int alternate = 19142;
                installer.configure("Bloco CI", 10, false, "survival", "normal", alternate, false, release.getName());
                cycle(base, runtime, release, false, true, alternate);
            }
        }
        if (!marker.isFile() || !files.hasWorld()) throw new Exception("Mundo não persistiu após reinicialização e mudança de porta");
        System.out.println("PASS: mundo persistiu entre perfis, filtro simulado e mudanças de porta; versão " + release.getName());
        System.out.println("::notice title=Bedrock ARM64::Inicialização, sinalização, console, parada e persistência passaram em ARM64; não houve login de jogador.");
        // Diagnostic only: startup does not prove a real Microsoft player login.
        installer.configure("Bloco CI", 10, true, "survival", "normal", port, true, release.getName());
        for (boolean direct : new boolean[]{true, false}) {
            try {
                cycle(base, runtime, release, direct, false, port);
                System.out.println("::notice title=Autenticação diagnóstica::online-mode=true iniciou em " + (direct ? "modo direto" : "PRoot") + "; nenhum login de jogador foi testado.");
            } catch (Exception error) {
                String detail = String.valueOf(error.getMessage()).replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D");
                if (detail.length() > 8000) detail = detail.substring(detail.length() - 8000);
                System.out.println("::warning title=Autenticação diagnóstica::" + detail);
            }
        }
    }
}
