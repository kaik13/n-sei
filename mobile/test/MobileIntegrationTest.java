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
    private static final AtomicBoolean SHUTDOWN_WARNING_REPORTED = new AtomicBoolean();
    private static void send(Process p, String command) throws Exception {
        p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8)); p.getOutputStream().flush();
    }
    private static Process launch(File base, File runtime, File release, boolean direct, boolean filtered) throws Exception {
        return launch(base, runtime, release, direct, filtered, false);
    }
    private static Process launch(File base, File runtime, File release, boolean direct, boolean filtered, boolean blockNetlink) throws Exception {
        ProcessBuilder builder = RuntimeCommand.create(base, runtime, release, direct, NetworkSettings.netherNet(release.getName()));
        if (filtered || blockNetlink) {
            // Install both restrictions in one pre-exec step. Nesting a second
            // Python interpreter inside the syscall trap kills it before PRoot
            // can intercept its startup syscalls.
            String name = filtered ? "seccomp-exec.py" : "seccomp-netlink.py";
            File helper = new File("mobile/test/" + name).getAbsoluteFile();
            if (!helper.isFile()) throw new Exception("Teste de filtro ausente: " + name);
            List<String> command = new ArrayList<>();
            command.add("python3"); command.add(helper.toString());
            if (filtered && blockNetlink) command.add("--deny-netlink");
            command.addAll(builder.command()); builder.command(command);
        }
        return builder.start();
    }
    private static String socketSnapshot(int port) {
        String suffix = ":" + String.format(java.util.Locale.ROOT, "%04X", port);
        StringBuilder found = new StringBuilder();
        for (String table : new String[]{"tcp", "tcp6", "udp", "udp6"}) {
            try {
                for (String row : java.nio.file.Files.readAllLines(new File("/proc/net/" + table).toPath(), StandardCharsets.US_ASCII)) {
                    String[] fields = row.trim().split("\\s+");
                    if (fields.length >= 10 && fields[1].endsWith(suffix)) {
                        if (found.length() > 0) found.append(' ');
                        // State 0A is LISTEN; 06 is TIME_WAIT. Include only the
                        // table, state and inode, not remote addresses.
                        found.append(table).append(':').append(fields[3]).append("/inode=").append(fields[9]);
                    }
                }
            } catch (Exception error) {
                if (found.length() > 0) found.append(' ');
                found.append(table).append(":indisponível");
            }
        }
        return found.length() == 0 ? "nenhum" : found.toString();
    }
    private static String describeHttp(int port) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/v1/join").openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode(), count = 0;
            try (java.io.InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (in != null) { byte[] buffer = new byte[4096]; int n;
                    while (count <= 65536 && (n = in.read(buffer)) != -1) count += n;
                }
            }
            return "HTTP=" + code + ", tipo=" + connection.getContentType() + ", bytes=" + count;
        } catch (Exception error) { return "HTTP indisponível: " + error.getClass().getSimpleName(); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static void cycle(File base, File runtime, File release, boolean direct, boolean filtered, int port, boolean lanVisibility) throws Exception {
        cycle(base, runtime, release, direct, filtered, port, lanVisibility, false);
    }
    private static void cycle(File base, File runtime, File release, boolean direct, boolean filtered, int port, boolean lanVisibility, boolean joinTest) throws Exception {
        boolean netherNet = NetworkSettings.netherNet(release.getName());
        if (joinTest && (!netherNet || !lanVisibility)) throw new Exception("Teste de entrada requer NetherNet e descoberta LAN");
        String mode = (direct ? "direct" : "proot") + (filtered ? " + filtro simulado" : "") + (joinTest ? " + netlink negado" : "");
        System.out.println("Testing " + mode + " on real ARM64 Linux, port " + port + ", LAN " + lanVisibility);
        String beforeSockets = socketSnapshot(port);
        System.out.println("Antes do processo: " + beforeSockets);
        Process p = launch(base, runtime, release, direct, filtered, joinTest);
        AtomicBoolean started = new AtomicBoolean(), listener = new AtomicBoolean(), listed = new AtomicBoolean(), playerConnected = new AtomicBoolean();
        AtomicBoolean shutdownFault = new AtomicBoolean();
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
                    if (line.contains("Player connected") && line.contains("BlocoCI")) playerConnected.set(true);
                    if (line.contains("Sigfault/Segbus while quitting")) shutdownFault.set(true);
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
                String state = p.isAlive() ? "ainda ativo" : "código " + p.exitValue();
                throw new Exception(mode + " não iniciou (" + state + "):\n" + tail.substring(Math.max(0, tail.length() - 2800)));
            } }
            String startedSockets = netherNet ? socketSnapshot(port) : "não verificado";
            System.out.println("Após Server started: " + startedSockets + "; listener=" + listener.get());
            deadline = System.currentTimeMillis() + 90000; boolean answered = false;
            while (!answered && p.isAlive() && System.currentTimeMillis() < deadline) {
                answered = netherNet ? listener.get() && NetherNetProbe.responds(port, !lanVisibility) : RakNetProbe.responds(port);
                if (!answered) Thread.sleep(250);
            }
            if (!answered) { synchronized (tail) {
                NetherNetProbe.Response response = netherNet ? NetherNetProbe.probe(port) : NetherNetProbe.Response.NONE;
                String diagnostics = "modo=" + mode + ", porta=" + port + ", LAN=" + lanVisibility + ", started=" + started.get() +
                    ", listener=" + listener.get() + ", resposta=" + response + ", processoVivo=" + p.isAlive() +
                    (netherNet ? ", " + describeHttp(port) + ", socketsAntes=" + beforeSockets +
                        ", socketsAoIniciar=" + startedSockets + ", socketsAgora=" + socketSnapshot(port) : "");
                StringBuilder compact = new StringBuilder();
                for (String line : tail.toString().split("\\n")) {
                    if (!line.contains("Contents of server.properties:") && line.length() < 800) compact.append(line).append('\n');
                }
                String last = compact.substring(Math.max(0, compact.length() - 2400));
                System.out.println("::error title=Diagnóstico NetherNet::" + (diagnostics + "\n" + last)
                    .replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D"));
                throw new Exception(mode + " não confirmou a sinalização: " + diagnostics + "\n" + last);
            } }
            if (netherNet) System.out.println("NetherNet local: LAN=" + lanVisibility + ", " + describeHttp(port));
            if (joinTest) {
                File clientScript = new File("mobile/nethernet-ci/join.cjs").getAbsoluteFile();
                if (!clientScript.isFile()) throw new Exception("Cliente de teste NetherNet ausente");
                String host = System.getenv("BLOCO_NETHER_TEST_HOST"), namespace = System.getenv("BLOCO_NETHER_TEST_NAMESPACE");
                if (host == null || host.isEmpty()) host = "127.0.0.1";
                String node = System.getenv("BLOCO_NODE_BIN"); if (node == null || node.isEmpty()) node = "node";
                List<String> clientCommand = new ArrayList<>();
                if (namespace != null && !namespace.isEmpty()) {
                    if (!namespace.matches("[A-Za-z0-9_.-]+") || host.startsWith("127.")) throw new Exception("Namespace de teste inválido");
                    clientCommand.add("sudo"); clientCommand.add("ip"); clientCommand.add("netns"); clientCommand.add("exec"); clientCommand.add(namespace);
                    clientCommand.add("sudo"); clientCommand.add("-u"); clientCommand.add(System.getProperty("user.name"));
                }
                clientCommand.add(node); clientCommand.add(clientScript.toString()); clientCommand.add(host); clientCommand.add("Bloco CI");
                System.out.println("Cliente NetherNet: " + (namespace == null || namespace.isEmpty() ? "mesmo namespace" : "namespace de rede separado") + ", servidor " + host);
                Process client = new ProcessBuilder(clientCommand).inheritIO().start();
                try {
                    if (!client.waitFor(110, TimeUnit.SECONDS)) throw new Exception("Cliente de teste excedeu o tempo limite");
                    if (client.exitValue() != 0) throw new Exception("Cliente NetherNet não concluiu a entrada offline; veja o log acima");
                } finally { if (client.isAlive()) client.destroyForcibly(); }
                deadline = System.currentTimeMillis() + 10000;
                while (!playerConnected.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                if (!playerConnected.get()) throw new Exception("O Bedrock não registrou a entrada do jogador de teste");
                System.out.println("PASS: " + mode + " — descoberta LAN, ICE/UDP e entrada offline com cliente automatizado");
            }
            send(p, "list"); deadline = System.currentTimeMillis() + 60000;
            while (!listed.get() && p.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            if (!listed.get()) throw new Exception(mode + " não respondeu ao comando list");
            send(p, "stop");
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new Exception(mode + " não encerrou corretamente");
            reader.join(10000);
            if (shutdownFault.get() && SHUTDOWN_WARNING_REPORTED.compareAndSet(false, true))
                System.out.println("::warning title=Encerramento Box64::Box64 relatou SIGSEGV/SIGBUS durante sua limpeza após o pedido de parada e retornou código zero. Isso não comprova perda nem integridade do mundo; preserve backups.");
            System.out.println("PASS: " + mode + " — inicialização, " + (netherNet ? "sinalização NetherNet TCP" : "RakNet UDP") + ", console e processo encerrado com código zero" + (shutdownFault.get() ? " (com aviso Box64 na limpeza)" : ""));
        } finally { if (p.isAlive()) p.destroyForcibly(); }
    }
    private static void testJava(File base, File runtime) throws Exception {
        JavaRuntimeInstaller.Progress progress = new JavaRuntimeInstaller.Progress() {
            @Override public void message(String text) { System.out.println("Java | " + text); }
            @Override public boolean cancelled() { return false; }
        };
        JavaRuntimeInstaller.install(base, progress);
        System.out.println("Java | " + JavaRuntimeInstaller.verify(base, runtime, progress));
        if (!JavaRuntimeInstaller.verified(base, runtime)) throw new Exception("Java não foi marcado como executado no Ubuntu");
        File sourceDir = new File(base, "java-test");
        if (!sourceDir.mkdirs()) throw new Exception("Não foi possível preparar o teste Java");
        File source = new File(sourceDir, "BlocoJavaProbe.java");
        String code = "public class BlocoJavaProbe { public static void main(String[] a) { int x=1; " +
            "for(int i=0;i<Integer.parseInt(a[0]);i++) x=(x*33+i)&0x7fffffff; System.out.println(\"JAVA_PROBE_OK \"+x); } }";
        java.nio.file.Files.write(source.toPath(), code.getBytes(StandardCharsets.UTF_8));
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, null, null, "--release", "8", "-d", sourceDir.toString(), source.toString()) != 0)
            throw new Exception("Compilação do programa Java de teste falhou");
        ProcessBuilder builder = RuntimeCommand.createJava(base, runtime, "-cp", "/bloco/java-test", "BlocoJavaProbe", "1000000");
        File filter = new File("mobile/test/seccomp-exec.py").getAbsoluteFile();
        List<String> command = new ArrayList<>();
        command.add("python3"); command.add(filter.toString()); command.add("--deny-netlink"); command.addAll(builder.command());
        builder.command(command);
        Process child = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = in.readLine()) != null) {
                    System.out.println("java + filtro | " + line);
                    synchronized (output) { if (output.length() < 20000) output.append(line).append('\n'); }
                }
            } catch (Exception ignored) { }
        });
        reader.setDaemon(true); reader.start();
        try {
            if (!child.waitFor(120, TimeUnit.SECONDS)) throw new Exception("Programa Java excedeu o tempo limite");
            reader.join(5000);
            String text; synchronized (output) { text = output.toString(); }
            if (child.exitValue() != 0 || !text.contains("JAVA_PROBE_OK "))
                throw new Exception("Java ARM64 não executou programa no Ubuntu sob filtro simulado: " + text);
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        System.out.println("PASS: Java ARM64 instalado e programa real executado no Ubuntu com filtros simulados");
        System.out.println("::notice title=Java ARM64::Java 21 instalado e programa Java executado no Ubuntu via PRoot em Linux ARM64 com filtros simulados; não é teste em celular físico.");
    }
    private static void blockedPort(File base, File runtime, File release, int port, boolean lanVisibility) throws Exception {
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
            // The blocker deliberately returns either valid-looking Bedrock
            // metadata or an empty 200. A probe alone is not sufficient: the
            // process must also report its own listener and successful start.
            if (!NetherNetProbe.responds(port, !lanVisibility)) throw new Exception("O servidor simulado não respondeu na porta ocupada");
            if (started.get() && listener.get()) throw new Exception("Outro servidor na porta ocupada passou pela verificação de prontidão");
            System.out.println("PASS: outro servidor em TCP " + port + " não foi confundido com o Bedrock iniciado (LAN=" + lanVisibility + ")");
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
        testJava(base, runtime);
        BedrockInstaller installer = new BedrockInstaller(base);
        File release = installer.prepare(new BedrockInstaller.Progress() {
            @Override public void message(String text) { System.out.println(text); }
            @Override public boolean cancelled() { return false; }
        });
        // Deliberately offline for an isolated LAN protocol test. The app
        // requires explicit confirmation before using this mode.
        int port = NetworkSettings.DEFAULT_PORT;
        installer.configure("Bloco CI", 10, false, "survival", "normal", port, true, release.getName());
        cycle(base, runtime, release, true, false, port, true);
        LocalFiles files = new LocalFiles(base);
        if (!files.hasWorld()) throw new Exception("Mundo não foi criado");
        File marker = new File(files.world(), "bloco-test-marker"); RuntimeInstaller.writeText(marker, "persistente");
        RuntimeInstaller.writeText(new File(runtime, ".bloco-runtime"), "ubuntu24-box64-v044-proot-v550-3\n");
        runtime = RuntimeInstaller.install(base, new FileInputStream(asset));
        if (!marker.isFile() || !files.hasWorld()) throw new Exception("Atualização do runtime alterou o mundo");
        cycle(base, runtime, release, false, false, port, true);
        if (NetworkSettings.netherNet(release.getName())) {
            // Both the emulator and the tracer inherit this simulated netlink
            // denial; the automated player stays outside the filter. This is
            // not a physical Android or Microsoft-account login test.
            cycle(base, runtime, release, true, false, port, true, true);
            cycle(base, runtime, release, false, true, port, true, true);
            String namespace = System.getenv("BLOCO_NETHER_TEST_NAMESPACE");
            if (namespace != null && !namespace.isEmpty())
                System.out.println("::notice title=Entrada Bedrock::Cliente offline em namespace de rede separado entrou no Bedrock real ARM64, nos modos direto e PRoot sob netlink negado simulado; não é teste de celular físico nem de conta Microsoft.");
            // An actual occupied TCP port is different from assuming the user's
            // phone has a conflict. Use a fresh port for the synthetic listener;
            // the direct -> PRoot cycles above still test a same-port restart.
            int occupied = 19152;
            installer.configure("Bloco CI", 10, false, "survival", "normal", occupied, true, release.getName());
            try (ServerSocket blocker = new ServerSocket()) {
                blocker.setReuseAddress(false); blocker.bind(new InetSocketAddress("0.0.0.0", occupied));
                AtomicBoolean emptyResponse = new AtomicBoolean();
                Thread otherServer = new Thread(() -> {
                    String json = "{\"name\":\"Outro servidor\",\"protocol\":2225,\"version\":\"1.26.63\",\"level\":\"Outro mundo\",\"players\":0,\"maxPlayers\":10}";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    byte[] header = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    while (!blocker.isClosed()) try (java.net.Socket connection = blocker.accept()) {
                        connection.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                        String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        if (emptyResponse.get()) connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        else { connection.getOutputStream().write(header); connection.getOutputStream().write(body); }
                        connection.getOutputStream().flush();
                    } catch (Exception ignored) { }
                });
                otherServer.setDaemon(true); otherServer.start();
                if (PortDiagnostics.test(true, false, occupied).available) throw new Exception("Diagnóstico não detectou conflito TCP");
                blockedPort(base, runtime, release, occupied, true);
                installer.configure("Bloco CI", 10, false, "survival", "normal", occupied, false, release.getName());
                emptyResponse.set(true);
                blockedPort(base, runtime, release, occupied, false);
                int alternate = 19142;
                installer.configure("Bloco CI", 10, false, "survival", "normal", alternate, true, release.getName());
                cycle(base, runtime, release, false, false, alternate, true);
                cycle(base, runtime, release, false, true, alternate, true);
                installer.configure("Bloco CI", 10, false, "survival", "normal", alternate, false, release.getName());
                cycle(base, runtime, release, false, false, alternate, false);
                cycle(base, runtime, release, false, true, alternate, false);
            }
        }
        if (!marker.isFile() || !files.hasWorld()) throw new Exception("Mundo não persistiu após reinicialização e mudança de porta");
        System.out.println("PASS: mundo persistiu entre perfis, filtro simulado e mudanças de porta; versão " + release.getName());
        System.out.println("::notice title=Bedrock ARM64::Inicialização, console, parada e persistência passaram em ARM64. Se NetherNet, cliente automatizado entrou em modo offline sob filtro de rede simulado; não é teste em celular nem login Microsoft.");
        // Diagnostic only: startup does not prove a real Microsoft player login.
        installer.configure("Bloco CI", 10, true, "survival", "normal", port, true, release.getName());
        for (boolean direct : new boolean[]{true, false}) {
            try {
                cycle(base, runtime, release, direct, false, port, true);
                System.out.println("::notice title=Autenticação diagnóstica::online-mode=true iniciou em " + (direct ? "modo direto" : "PRoot") + "; nenhum login de jogador foi testado.");
            } catch (Exception error) {
                String detail = String.valueOf(error.getMessage()).replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D");
                if (detail.length() > 8000) detail = detail.substring(detail.length() - 8000);
                System.out.println("::warning title=Autenticação diagnóstica::" + detail);
            }
        }
    }
}
