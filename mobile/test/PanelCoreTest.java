package br.com.bloco.servidor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PanelCoreTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static byte[] fakeElf(boolean arm) {
        byte[] data = new byte[64]; data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = 2; data[5] = 1; data[18] = (byte) (arm ? 183 : 62); return data;
    }
    private static byte[] javaArchive(boolean arm, boolean traversal) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"release", "bin/java", "lib/modules", "lib/libjava.so", "lib/libjli.so", "lib/server/libjvm.so", "legal/java.base/LICENSE"}) {
                zip.putNextEntry(new ZipEntry("jdk4py/java-runtime/" + name));
                zip.write(name.equals("release") ? "JAVA_VERSION=\"21.0.8\"\n".getBytes(StandardCharsets.UTF_8) : name.equals("bin/java") ? fakeElf(arm) : new byte[]{1, 2, 3});
                zip.closeEntry();
            }
            if (traversal) { zip.putNextEntry(new ZipEntry("../outside")); zip.write(42); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
    private static File archive(File dir, String name, byte[] bytes) throws Exception {
        File file = new File(dir, name); try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); } return file;
    }
    private static void rejected(File base, byte[] data, String expectedHash) throws Exception {
        File file = archive(base.getParentFile(), "malicious.zip", data);
        try { JavaRuntimeInstaller.installArchive(base, file, data.length, expectedHash, null); throw new AssertionError("Java inválido aceito"); }
        catch (java.io.IOException expected) { }
    }
    private static void javaTests(File root) throws Exception {
        File base = new File(root, "base"); base.mkdirs(); byte[] good = javaArchive(true, false);
        File wheel = archive(root, "good.whl", good);
        JavaRuntimeInstaller.installArchive(base, wheel, good.length, hash(good), null);
        check(JavaRuntimeInstaller.installed(base), "instalação Java falsa não foi reconhecida");
        check(!JavaRuntimeInstaller.verified(base, new File(base, "runtime")), "Java não executado foi marcado como verificado");
        check(new File(base, "java/bin/java").canExecute(), "launcher Java não é executável");
        rejected(base, good, "0000000000000000000000000000000000000000000000000000000000000000");
        check(JavaRuntimeInstaller.installed(base), "checksum incorreto danificou instalação anterior");
        byte[] badElf = javaArchive(false, false); rejected(base, badElf, hash(badElf));
        check(JavaRuntimeInstaller.installed(base), "ELF incorreto danificou instalação anterior");
        byte[] traversal = javaArchive(true, true); rejected(base, traversal, hash(traversal));
        check(!new File(base, "outside").exists() && !new File(root, "outside").exists(), "arquivo escapou da extração Java");
        check(JavaRuntimeInstaller.installed(base), "extração maliciosa danificou instalação anterior");
        File previous = new File(base, ".java-anterior");
        check(new File(base, "java").renameTo(previous), "não foi possível simular interrupção");
        rejected(base, good, "0000000000000000000000000000000000000000000000000000000000000000");
        check(JavaRuntimeInstaller.installed(base), "Java anterior não foi recuperado antes da falha");

        String real = System.getenv("BLOCO_JAVA_WHEEL");
        if (real != null && !real.isEmpty()) {
            File actual = new File(real), actualBase = new File(root, "actual");
            JavaRuntimeInstaller.installArchive(actualBase, actual, JavaRuntimeInstaller.ARCHIVE_SIZE, JavaRuntimeInstaller.SHA256, null);
            check(JavaRuntimeInstaller.installed(actualBase), "o pacote oficial de Java não foi instalado");
            System.out.println("PASS: arquivo ARM64 real de Java verificado e extraído, sem executá-lo no host x86");
        }
    }
    private static void logTests(File root) throws Exception {
        File base = new File(root, "logs-base"); PersistentLog log = new PersistentLog(base);
        log.append("um\ndois\rtrês");
        String initial = log.tail(4096);
        check(initial.contains("um dois três") && initial.indexOf('\n') == initial.lastIndexOf('\n'), "linha de log não foi normalizada");
        AtomicReference<Throwable> error = new AtomicReference<>(); List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int index = t;
            Thread worker = new Thread(() -> { try {
                String payload = new String(new char[600]).replace('\0', 'x');
                for (int i = 0; i < 500; i++) new PersistentLog(base).append("worker-" + index + "-" + i + " " + payload);
            } catch (Throwable failure) { error.compareAndSet(null, failure); } });
            workers.add(worker); worker.start();
        }
        for (Thread worker : workers) worker.join();
        if (error.get() != null) throw new AssertionError(error.get());
        File dir = new File(base, "logs");
        check(new File(dir, "servidor-anterior.log").isFile(), "histórico não foi rotacionado");
        check(new File(dir, "servidor.log").length() <= 1024 * 1024, "histórico não está limitado");
        String tail = log.tail(65536);
        check(tail.getBytes(StandardCharsets.UTF_8).length <= 65536 && tail.contains("worker-"), "leitura de histórico inválida");
    }
    private static void settingsTests() {
        check(PerformanceSettings.valid(5, 4, 0) && PerformanceSettings.valid(32, 12, 32), "limites válidos rejeitados");
        check(!PerformanceSettings.valid(4, 4, 0) && !PerformanceSettings.valid(5, 6, 0) &&
            !PerformanceSettings.valid(32, 13, 0) && !PerformanceSettings.valid(5, 4, 33), "limites inválidos aceitos");
        check(PerformanceSettings.view(100) == 32 && PerformanceSettings.tick(12, 5) == 5 && PerformanceSettings.threads(-2) == 0, "ajustes não limitados");
        check("list".equals(ConsoleCommand.normalize(" /list ")), "comando não normalizado");
        for (String bad : new String[]{"", "/", "list\nstop", "list\rstop", "a\0b", new String(new char[501]).replace('\0', 'x')}) {
            try { ConsoleCommand.normalize(bad); throw new AssertionError("comando inválido aceito"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("bloco-panel-test").toFile();
        try { javaTests(root); logTests(root); settingsTests(); System.out.println("PASS: Java, recuperação, histórico, console e ajustes do painel"); }
        finally { SafeZip.deleteTree(root); }
    }
}
