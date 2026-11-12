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
        new BedrockInstaller(root).configure("Teste", 2, true, "creative", "hard");
        String props = RuntimeInstaller.readText(new File(files.store(), "server.properties"), 4096);
        check(props.contains("max-players=10") && props.contains("online-mode=true") && props.contains("gamemode=creative") && props.contains("transport=raknet"), "configuração incorreta");
    }
    private static byte[] runtime(String target) throws Exception {
        JSONObject manifest = new JSONObject(); manifest.put("format", 1);
        manifest.put("links", new JSONArray().put(new JSONArray().put("rootfs/lib").put(target)));
        manifest.put("executable", new JSONArray().put("proot").put("rootfs/opt/box64/box64"));
        return zip("proot", "proot", "rootfs/opt/box64/box64", "box", "rootfs/usr/lib/test", "lib", "runtime-manifest.json", manifest.toString());
    }
    private static void testRuntime(File root) throws Exception {
        File installed = RuntimeInstaller.install(root, new ByteArrayInputStream(runtime("usr/lib")));
        check(new File(installed, "proot").canExecute(), "proot sem execução");
        check(new File(installed, "rootfs/lib/test").isFile(), "link não instalado");
        // A healthy install is reused without reading a replacement asset.
        RuntimeInstaller.install(root, new ByteArrayInputStream(new byte[0]));
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
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("bloco-core-test").toFile();
        try { testLocalFiles(new File(root, "files")); testRuntime(new File(root, "runtime")); testProbe();
            check(AppState.beginFileOperation(), "gate arquivo"); check(!AppState.beginHosting(), "gate execução");
            AppState.finishFileOperation(); check(AppState.beginHosting(), "gate servidor");
            check(!AppState.beginFileOperation(), "gate importação"); AppState.finish(null);
            System.out.println("Core móvel: testes de mundo, packs, backup, runtime, rede e exclusão mútua passaram.");
        } finally { SafeZip.deleteTree(root); }
    }
}
