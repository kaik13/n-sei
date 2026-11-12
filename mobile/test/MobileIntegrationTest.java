package br.com.bloco.servidor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the genuine downloaded Bedrock executable through both bundled ARM64 profiles. */
public final class MobileIntegrationTest {
    private static void send(Process p, String command) throws Exception {
        p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8)); p.getOutputStream().flush();
    }
    private static void cycle(File base, File runtime, File release, boolean direct) throws Exception {
        String mode = direct ? "direct" : "proot";
        System.out.println("Testing " + mode + " on real ARM64 Linux");
        Process p = RuntimeCommand.create(base, runtime, release, direct).start();
        AtomicBoolean started = new AtomicBoolean(), listed = new AtomicBoolean();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(mode + " | " + line);
                    synchronized (tail) { tail.append(line).append('\n'); if (tail.length() > 10000) tail.delete(0, tail.length() - 10000); }
                    if (line.contains("Server started.")) started.set(true);
                    if (line.contains("players online")) listed.set(true);
                }
            } catch (Exception ignored) { }
        }); reader.start();
        try {
            long deadline = System.currentTimeMillis() + 3 * 60 * 1000L;
            while (!started.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            if (!started.get()) { synchronized (tail) { throw new Exception(mode + " não iniciou:\n" + tail); } }
            deadline = System.currentTimeMillis() + 90000; boolean answered = false;
            while (!answered && p.isAlive() && System.currentTimeMillis() < deadline) answered = RakNetProbe.responds(19132);
            if (!answered) throw new Exception(mode + " não respondeu ao teste RakNet na porta 19132");
            send(p, "list"); deadline = System.currentTimeMillis() + 60000;
            while (!listed.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            if (!listed.get()) throw new Exception(mode + " não respondeu ao comando list");
            send(p, "stop");
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new Exception(mode + " não encerrou corretamente");
            reader.join(10000);
            System.out.println("PASS: " + mode + " — inicialização, RakNet, console e parada limpa");
        } finally { if (p.isAlive()) p.destroyForcibly(); }
    }
    public static void main(String[] args) throws Exception {
        File asset = new File(args[0]), base = new File(args[1]);
        File runtime = RuntimeInstaller.install(base, new FileInputStream(asset));
        System.out.println("PASS: runtime asset extracted with Android's installer logic");
        // The Android service writes its active network's DNS to the guest;
        // mirror that setup using the CI host's resolver configuration.
        java.nio.file.Path hostDns = new File("/etc/resolv.conf").toPath();
        if (java.nio.file.Files.isRegularFile(hostDns))
            java.nio.file.Files.copy(hostDns, new File(runtime, "rootfs/etc/resolv.conf").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        BedrockInstaller installer = new BedrockInstaller(base);
        File release = installer.prepare(new BedrockInstaller.Progress() {
            @Override public void message(String text) { System.out.println(text); }
            @Override public boolean cancelled() { return false; }
        });
        // Deliberately offline for a hermetic LAN protocol test; production default is authenticated.
        installer.configure("Bloco CI", 10, false, "survival", "normal");
        cycle(base, runtime, release, true);
        LocalFiles files = new LocalFiles(base);
        if (!files.hasWorld()) throw new Exception("Mundo não foi criado");
        File marker = new File(files.world(), "bloco-test-marker"); RuntimeInstaller.writeText(marker, "persistente");
        cycle(base, runtime, release, false);
        if (!marker.isFile() || !files.hasWorld()) throw new Exception("Mundo não persistiu após reinicialização");
        System.out.println("PASS: mundo persistiu entre perfis e reinicializações; versão " + release.getName());
        // Diagnostic only: the upstream service may be unavailable in CI and a
        // successful startup is not proof of a real Microsoft player login.
        installer.configure("Bloco CI", 10, true, "survival", "normal");
        for (boolean direct : new boolean[]{true, false}) {
            try {
                cycle(base, runtime, release, direct);
                System.out.println("::notice title=Autenticação diagnóstica::online-mode=true iniciou em " + (direct ? "modo direto" : "PRoot") + "; nenhum login de jogador foi testado.");
            } catch (Exception error) {
                String detail = String.valueOf(error.getMessage()).replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D");
                if (detail.length() > 8000) detail = detail.substring(detail.length() - 8000);
                System.out.println("::warning title=Autenticação diagnóstica::" + detail);
            }
        }
    }
}
