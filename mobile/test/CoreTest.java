package br.com.bloco.servidor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CoreTest {
    private static byte[] zip(Object... pairs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) pairs[i]));
                Object value = pairs[i + 1];
                zip.write(value instanceof byte[] ? (byte[]) value : value.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static void write(File f, String s) throws Exception { RuntimeInstaller.writeText(f, s); }
    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static byte[] world(String marker) throws Exception { return zip("level.dat", marker, "db/data", "world-db"); }
    private static String manifest(String id, String type) {
        return "{\"format_version\":2,\"header\":{\"name\":\"Teste\",\"uuid\":\"" + id + "\",\"version\":[1,2,3]},\"modules\":[{\"type\":\"" + type + "\"}]}";
    }
    private static void testLocalFiles(File root) throws Exception {
        LocalFiles files = new LocalFiles(root);
        files.importWorld(new ByteArrayInputStream(world("original")));
        check(files.hasWorld(), "mundo não importado");
        files.importWorld(new ByteArrayInputStream(world("novo")));
        check(files.hasBackup(), "mundo antigo não preservado");
        check(RuntimeInstaller.readText(new File(files.world(), "level.dat"), 100).equals("novo"), "mundo novo incorreto");
        files.restoreLatestBackup();
        check(RuntimeInstaller.readText(new File(files.world(), "level.dat"), 100).equals("original"), "restauração incorreta");
        File release = new File(files.store(), "versoes/1.26.63.1"); check(release.mkdirs(), "release");
        write(new File(files.store(), "versao.txt"), "1.26.63.1\n");
        String one = "11111111-2222-3333-4444-555555555555";
        String two = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        byte[] behavior = zip("manifest.json", manifest(one, "data"), "functions/test.mcfunction", "say hi");
        byte[] texture = zip("manifest.json", manifest(two, "resources"), "textures/test.png", "fake");
        files.importPacks(new ByteArrayInputStream(zip("pack-a.mcpack", behavior, "pack-b.mcpack", texture)));
        check(new File(files.store(), "behavior_packs/bloco-" + one + "/manifest.json").isFile(), "pack não armazenado");
        check(new File(release, "resource_packs/bloco-" + two + "/manifest.json").isFile(), "pack não copiado para release");
        check(new File(files.world(), "behavior_packs/bloco-" + one + "/manifest.json").isFile(), "pack não incluído no mundo");
        JSONArray active = new JSONArray(RuntimeInstaller.readText(new File(files.world(), "world_behavior_packs.json"), 10000));
        check(active.length() == 1 && one.equals(active.getJSONObject(0).getString("pack_id")), "ativação inválida");
        check(files.activePacks().size() == 2, "painel não listou os conteúdos ativos");
        files.disablePack("resource", two);
        check(files.activePacks().size() == 1 && new File(files.world(), "resource_packs/bloco-" + two + "/manifest.json").isFile(), "desativação apagou arquivos ou não atualizou a lista");
        files.importPacks(new ByteArrayInputStream(texture));
        check(files.activePacks().size() == 2, "reimportação não reativou a textura");
        try { files.disablePack("resource", "../../fora"); throw new AssertionError("identificador inseguro aceito"); }
        catch (java.io.IOException expected) { }
        ByteArrayOutputStream exported = new ByteArrayOutputStream(); files.exportWorld(exported);
        File restored = new File(root, "export-check");
        SafeZip.extract(new ByteArrayInputStream(exported.toByteArray()), restored, new SafeZip.Budget(1000000, 1000));
        check(new File(restored, "resource_packs/bloco-" + two + "/manifest.json").isFile(), "exportação sem pack");
        try { files.importWorld(new ByteArrayInputStream(zip("../fora", "bad"))); throw new AssertionError("traversal"); }
        catch (java.io.IOException expected) { }
        check(RuntimeInstaller.readText(new File(files.world(), "level.dat"), 100).equals("original"), "mundo alterado por ZIP inválido");
        try { files.importPacks(new ByteArrayInputStream(zip("manifest.json", "{invalid"))); throw new AssertionError("manifesto inválido"); }
        catch (java.io.IOException expected) { }
        check(new File(files.world(), "world_resource_packs.json").isFile(), "conteúdo anterior não preservado");
        File properties = new File(files.store(), "server.properties");
        write(properties, "# Comentário preservado\ntransport=raknet\nserver-udp-ports=1000\n server-udp-ports=1001\nonline-mode=false\n");
        BedrockInstaller installer = new BedrockInstaller(root);
        installer.configure("Teste", 2, true, "creative", "hard", 19142, false, "1.26.63.1");
        String props = RuntimeInstaller.readText(properties, 4096);
        check(props.contains("max-players=2") && props.contains("online-mode=true") && props.contains("gamemode=creative") &&
            props.contains("transport=nethernet") && props.contains("server-port=19142") && props.contains("server-portv6=19143") &&
            props.contains("server-udp-ports=19142") && props.contains("enable-lan-visibility=false") && props.contains("# Comentário preservado"), "configuração NetherNet incorreta");
        check(props.split("(?m)^server-udp-ports=", -1).length == 2 && !props.contains("server-udp-ports=100"), "configuração UDP duplicada");
        check("original".equals(RuntimeInstaller.readText(new File(files.world(), "level.dat"), 100)), "configurar rede alterou o mundo");
        installer.configure("Teste; LAN", 3, true, "survival", "normal", 19142, true, "1.26.63.1", 8, 6, 4);
        props = RuntimeInstaller.readText(properties, 4096);
        check(props.contains("server-name=Teste  LAN") && props.contains("view-distance=8") &&
            props.contains("tick-distance=6") && props.contains("max-threads=4") && props.contains("max-players=3"), "configuração de desempenho incorreta");
        try { installer.configure("Teste", 10, false, "survival", "normal", 19132, true, "1.26.63.1", 5, 9, 0); throw new AssertionError("desempenho inválido aceito"); }
        catch (java.io.IOException expected) { }
        check(props.equals(RuntimeInstaller.readText(properties, 4096)), "configuração inválida alterou propriedades");
        installer.configure("Teste", 10, false, "survival", "normal", 19132, true, "1.21.120.1");
        props = RuntimeInstaller.readText(properties, 4096);
        check(props.contains("transport=raknet") && props.contains("server-port=19132") && props.contains("server-portv6=19133") &&
            props.contains("enable-lan-visibility=true") && props.contains("online-mode=false") && props.contains("max-players=5"), "configuração legada ou limite de cinco vagas incorreto");
        try { installer.configure("Teste", 10, false, "survival", "normal", 7551, true, "1.26.63.1"); throw new AssertionError("porta de descoberta aceita"); }
        catch (java.io.IOException expected) { }
    }
    private static byte[] runtime(String target, boolean ca, boolean cleanupFix, boolean ifaddrFix) throws Exception {
        JSONObject manifest = new JSONObject(); manifest.put("format", 1); manifest.put("box64-tcp-reuse", 1);
        if (cleanupFix) manifest.put("box64-cleanup-thread-safety", 1);
        if (ifaddrFix) { manifest.put("box64-ifaddrs-ioctl", 1); manifest.put("proot-ifaddrs-ioctl", 1); }
        manifest.put("links", new JSONArray().put(new JSONArray().put("rootfs/lib").put(target)));
        manifest.put("executable", new JSONArray().put("proot").put("rootfs/opt/box64/box64"));
        if (ca) return zip("proot", "proot", "rootfs/opt/box64/box64", "box", "rootfs/usr/lib/test", "lib",
            "rootfs/etc/ssl/certs/ca-certificates.crt", "test-ca", "runtime-manifest.json", manifest.toString());
        return zip("proot", "proot", "rootfs/opt/box64/box64", "box", "rootfs/usr/lib/test", "lib", "runtime-manifest.json", manifest.toString());
    }
    private static byte[] runtime(String target, boolean ca, boolean cleanupFix) throws Exception { return runtime(target, ca, cleanupFix, true); }
    private static byte[] runtime(String target, boolean ca) throws Exception { return runtime(target, ca, true); }
    private static byte[] runtime(String target) throws Exception { return runtime(target, true); }
    private static void testRuntime(File root) throws Exception {
        File installed = RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib")));
        check(new File(installed, "proot").canExecute(), "proot sem execução");
        check(new File(installed, "rootfs/lib/test").isFile(), "link não instalado");
        // A healthy install is reused without reading a replacement asset.
        RuntimeInstaller.install(root, new ByteArrayInputStream(new byte[0]));
        // A runtime upgrade must replace only the environment, never the world.
        File existingWorld = new File(root, "servidor/worlds/Bloco/level.dat");
        write(existingWorld, "mundo-existente");
        write(new File(installed, ".bloco-runtime"), "runtime-antigo\n");
        try {
            RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib", true, false)));
            throw new AssertionError("runtime sem proteção concorrente aceito");
        } catch (java.io.IOException expected) { }
        check(new File(installed, "proot").isFile() && "mundo-existente".equals(RuntimeInstaller.readText(existingWorld, 100)),
              "runtime rejeitado alterou a instalação ou o mundo");
        try {
            RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib", true, true, false)));
            throw new AssertionError("runtime sem correção de interfaces aceito");
        } catch (java.io.IOException expected) { }
        check("mundo-existente".equals(RuntimeInstaller.readText(existingWorld, 100)), "runtime rejeitado alterou o mundo");
        RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib")));
        check("mundo-existente".equals(RuntimeInstaller.readText(existingWorld, 100)), "atualização alterou o mundo");
        File ca = new File(installed, "rootfs/etc/ssl/certs/ca-certificates.crt");
        File release = new File(root, "servidor/versoes/1.0.0"); check(release.mkdirs(), "release de teste");
        ProcessBuilder direct = RuntimeCommand.create(root, installed, release, true);
        ProcessBuilder proot = RuntimeCommand.create(root, installed, release, false);
        check(ca.toString().equals(direct.environment().get("SSL_CERT_FILE")), "certificado direto incorreto");
        check("/etc/ssl/certs/ca-certificates.crt".equals(proot.environment().get("SSL_CERT_FILE")), "certificado proot incorreto");
        check("glibc.pthread.rseq=0".equals(direct.environment().get("GLIBC_TUNABLES")) &&
              "glibc.pthread.rseq=0".equals(proot.environment().get("GLIBC_TUNABLES")), "proteção rseq ausente");
        check(!direct.environment().containsKey("BLOCO_REUSE_TCP") && !proot.environment().containsKey("BLOCO_REUSE_TCP") &&
              "1".equals(RuntimeCommand.create(root, installed, release, true, true).environment().get("BLOCO_REUSE_TCP")) &&
              "1".equals(RuntimeCommand.create(root, installed, release, false, true).environment().get("BLOCO_REUSE_TCP")),
              "compatibilidade TCP não está restrita ao NetherNet nos dois perfis");
        check("1".equals(proot.environment().get("PROOT_NO_SECCOMP")), "configuração do PRoot ausente");
        check("/tmp".equals(proot.environment().get("TMPDIR")) &&
              new File(root, "tmp").toString().equals(proot.environment().get("PROOT_TMP_DIR")), "temporários incorretos");
        check(proot.command().contains("-r") && !proot.command().contains("-R") &&
              proot.command().contains(new File(root, "servidor") + ":/srv/bloco"), "binds do servidor incorretos");
        check(ca.delete(), "não foi possível simular certificado ausente");
        RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib")));
        check(ca.isFile(), "runtime não restaurou o certificado ausente");
        try { RuntimeInstaller.install(new File(root, "sem-ca"), new ByteArrayInputStream(runtime("usr/lib", false))); throw new AssertionError("certificados ausentes aceitos"); }
        catch (java.io.IOException expected) { }
        try { RuntimeInstaller.install(new File(root, "bad"), new ByteArrayInputStream(runtime("../../fora"))); throw new AssertionError("link inseguro"); }
        catch (java.io.IOException expected) { }
        check(!new File(root, "fora").exists(), "link escapou");
    }
    private static void testProbe() throws Exception {
        final byte[] magic = {0, (byte)255, (byte)255, 0, (byte)254, (byte)254, (byte)254, (byte)254, (byte)253, (byte)253, (byte)253, (byte)253, 0x12,0x34,0x56,0x78};
        long timestamp = 123456789L;
        byte[] pong = java.nio.ByteBuffer.allocate(38).put((byte)0x1c).putLong(timestamp)
            .putLong(0x1020304050607080L).put(magic).putShort((short)3).put(new byte[]{'M','C',';'}).array();
        check(RakNetProbe.validPong(pong, timestamp), "pong RakNet válido rejeitado");
        check(!RakNetProbe.validPong(pong, timestamp + 1), "pong com timestamp incorreto");
        byte[] oldOffset = new byte[35]; oldOffset[0] = 0x1c; System.arraycopy(magic, 0, oldOffset, 9, magic.length);
        check(!RakNetProbe.validPong(oldOffset, 0), "pong com magic no GUID aceito");
        byte[] truncated = java.util.Arrays.copyOf(pong, 37);
        check(!RakNetProbe.validPong(truncated, timestamp), "pong truncado aceito");
        byte[] bad = pong.clone(); bad[17] ^= 1;
        check(!RakNetProbe.validPong(bad, timestamp), "pong com magic inválido aceito");
        byte[] accepted = new byte[28]; accepted[0] = 0x06; System.arraycopy(magic, 0, accepted, 1, magic.length);
        check(RakNetProbe.validConnectionReply(accepted), "resposta 0x06 rejeitada");
        byte[] incompatible = new byte[26]; incompatible[0] = 0x19; incompatible[1] = 11;
        System.arraycopy(magic, 0, incompatible, 2, magic.length);
        check(RakNetProbe.validConnectionReply(incompatible), "resposta 0x19 rejeitada");
        check(!RakNetProbe.validConnectionReply(java.util.Arrays.copyOf(incompatible, 25)), "resposta 0x19 truncada aceita");
        try (DatagramSocket server = new DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread worker = new Thread(() -> { try {
                byte[] buf = new byte[1024]; DatagramPacket request = new DatagramPacket(buf, buf.length); server.receive(request);
                byte[] answer = new byte[35]; answer[0] = 0x1c;
                System.arraycopy(buf, 1, answer, 1, 8); // echo the actual ping time
                java.nio.ByteBuffer.wrap(answer, 9, 8).putLong(0x1020304050607080L);
                System.arraycopy(magic, 0, answer, 17, magic.length);
                server.send(new DatagramPacket(answer, answer.length, request.getSocketAddress()));
            } catch (Exception ignored) { } }); worker.start();
            check(RakNetProbe.responds(server.getLocalPort()), "probe RakNet não reconheceu pong real"); worker.join(3000);
        }
    }
    private static boolean netherResponse(String response) throws Exception { return netherResponse(response, false); }
    private static boolean netherResponse(String response, boolean allowEmptyStatus) throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            final boolean[] requestOk = { false };
            Thread worker = new Thread(() -> { try (java.net.Socket connection = server.accept()) {
                connection.setSoTimeout(2000);
                java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                requestOk[0] = "GET /v1/join HTTP/1.1".equals(input.readLine());
                String header; while ((header = input.readLine()) != null && !header.isEmpty()) { }
                connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8)); connection.getOutputStream().flush();
            } catch (Exception ignored) { } });
            worker.setDaemon(true); worker.start();
            boolean result = NetherNetProbe.responds(server.getLocalPort(), allowEmptyStatus); worker.join(3000);
            check(requestOk[0], "probe não solicitou /v1/join");
            return result;
        }
    }
    private static void testNetherNetAndPorts() throws Exception {
        check(NetworkSettings.netherNet("1.26.63.1") && NetworkSettings.netherNet("1.26.50.5") &&
            !NetworkSettings.netherNet("1.26.49.1") && !NetworkSettings.netherNet("1.21.130.4"), "detecção de transporte por versão");
        check("19142".equals(NetworkSettings.udpPorts("1.26.63.1", 19142, 10)) &&
            "19142-19151".equals(NetworkSettings.udpPorts("1.26.52.3", 19142, 10)) &&
            "65506-65535".equals(NetworkSettings.udpPorts("1.26.52.3", 65535, 30)) &&
            "7552-7561".equals(NetworkSettings.udpPorts("1.26.52.3", 7545, 10)), "intervalo UDP incorreto");
        check(!NetworkSettings.validPort(1023) && !NetworkSettings.validPort(7551) && NetworkSettings.validPort(1024) &&
            NetworkSettings.validPort(65535) && !NetworkSettings.validPort(65536) && NetworkSettings.legacyIpv6Port(65535) == 65534, "limites da porta");
        check(NetherNetProbe.listenerReported("[INFO] Accepting clients on [::]:19142", 19142) &&
            !NetherNetProbe.listenerReported("[INFO] Accepting clients on [::]:191420", 19142) &&
            !NetherNetProbe.listenerReported("Server started.", 19142), "detecção do listener NetherNet");
        String json = "{\"name\":\"Bloco\",\"protocol\":2225,\"version\":\"1.26.63\",\"level\":\"Bloco\",\"players\":0,\"maxPlayers\":10,\"gameType\":0}";
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n";
        check(netherResponse(header + json), "status NetherNet válido rejeitado");
        check(netherResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(json.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + json + "\r\n0\r\n\r\n"), "status chunked rejeitado");
        check(!netherResponse(header + "{}"), "status HTTP truncado aceito");
        check(!netherResponse("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"), "HTTP 404 aceito");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + json.length() + "\r\n\r\n" + json), "tipo HTTP incorreto aceito");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"), "JSON sem status aceito");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n{}"), "resposta grande demais aceita");
        String empty = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        check(!netherResponse(empty), "resposta vazia aceita quando a descoberta LAN está ativa");
        check(netherResponse(empty, true), "resposta 200 vazia com descoberta LAN desligada rejeitada");
        check(netherResponse(header + json, true), "metadados válidos rejeitados com descoberta LAN desligada");
        check(netherResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", true), "resposta chunked vazia rejeitada");
        check(!netherResponse("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n", true), "404 vazio aceito");
        check(!netherResponse("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n", true), "204 vazio aceito como status Bedrock");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n", true), "resposta vazia truncada aceita");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", true), "enquadramento HTTP ambíguo aceito");
        check(!netherResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n", true), "codificação HTTP desconhecida aceita");
        check(!netherResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}", true), "JSON inválido aceito como resposta vazia");
        try (java.net.ServerSocket tcp = new java.net.ServerSocket(0)) {
            check(!PortDiagnostics.test(true, false, tcp.getLocalPort()).available, "conflito TCP não detectado");
        }
        try (DatagramSocket udp = new DatagramSocket(0)) {
            check(!PortDiagnostics.test(false, false, udp.getLocalPort()).available, "conflito UDP não detectado");
        }
        PortDiagnostics.Result invalid = PortDiagnostics.test(true, false, -1);
        check(!invalid.available && !invalid.detail.isEmpty(), "falha de bind sem diagnóstico");
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("bloco-core-test").toFile();
        try { testLocalFiles(new File(root, "files")); testRuntime(new File(root, "runtime")); testProbe(); testNetherNetAndPorts();
            check(AppState.beginFileOperation(), "gate arquivo"); check(!AppState.beginHosting(), "gate execução");
            AppState.finishFileOperation(); check(AppState.beginHosting(), "gate servidor");
            check(!AppState.beginFileOperation(), "gate importação"); AppState.finish(null);
            System.out.println("Core móvel: testes de mundo, packs, backup, runtime, rede e exclusão mútua passaram.");
        } finally { SafeZip.deleteTree(root); }
    }
}
