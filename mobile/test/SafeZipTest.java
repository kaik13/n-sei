package br.com.bloco.servidor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SafeZipTest {
    private static byte[] zip(String... pairs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i]));
                out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static void rejected(byte[] bytes, File out, SafeZip.Budget budget) throws Exception {
        try { SafeZip.extract(new ByteArrayInputStream(bytes), out, budget); throw new AssertionError("importação deveria falhar"); }
        catch (java.io.IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("bloco-zip-test").toFile();
        try {
            File ok = new File(dir, "ok");
            SafeZip.extract(new ByteArrayInputStream(zip("db/level", "abc", "level.dat", "world")), ok, new SafeZip.Budget(30, 10));
            if (!new File(ok, "db/level").isFile()) throw new AssertionError("arquivo ausente");
            rejected(zip("../fora", "x"), new File(dir, "traversal"), new SafeZip.Budget(10, 5));
            if (new File(dir, "fora").exists()) throw new AssertionError("traversal");
            rejected(zip("a/./x", "a", "a/x", "b"), new File(dir, "duplicate"), new SafeZip.Budget(10, 5));
            rejected(zip("grande", "12345678901"), new File(dir, "size"), new SafeZip.Budget(10, 5));
            rejected(zip("a", "x", "b", "y"), new File(dir, "count"), new SafeZip.Budget(10, 1));
            rejected(zip("C:/windows", "x"), new File(dir, "drive"), new SafeZip.Budget(10, 5));
            File world = new File(dir, "world"); new File(world, "db").mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(world, "level.dat"))) { out.write(7); }
            try (FileOutputStream out = new FileOutputStream(new File(world, "db/data"))) { out.write(9); }
            ByteArrayOutputStream result = new ByteArrayOutputStream(); SafeZip.exportWorld(world, result);
            File restored = new File(dir, "restored");
            SafeZip.extract(new ByteArrayInputStream(result.toByteArray()), restored, new SafeZip.Budget(100, 10));
            if (!new File(restored, "level.dat").isFile() || !new File(restored, "db/data").isFile()) throw new AssertionError("restauração");
            Files.createSymbolicLink(new File(world, "evil").toPath(), new File(dir, "ok").toPath());
            try { SafeZip.exportWorld(world, new ByteArrayOutputStream()); throw new AssertionError("link aceito"); }
            catch (java.io.IOException expected) { }
            System.out.println("SafeZip: todos os testes passaram");
        } finally { SafeZip.deleteTree(dir); }
    }
}
