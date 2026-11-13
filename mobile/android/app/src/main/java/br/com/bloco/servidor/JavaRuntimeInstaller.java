package br.com.bloco.servidor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Installs a pinned native ARM64 OpenJDK runtime independently of the world. */
public final class JavaRuntimeInstaller {
    public interface Progress { void message(String text); boolean cancelled(); }

    // This is a Java 21 runtime, not a Minecraft Java Edition server. The
    // upstream jdk4py wheel contains a jlink-created OpenJDK runtime and its
    // notices; the Python package itself is not installed or executed.
    public static final String JAVA_VERSION = "21.0.8";
    public static final String JAVA_ID = "jdk4py-21.0.8.2-linux-aarch64";
    public static final String URL = "https://files.pythonhosted.org/packages/9d/0c/24a7d8e4c96c3843b8163ba7a9be939b48fc181fb3263bd4fd9289ed8f41/jdk4py-21.0.8.2-py3-none-manylinux_2_17_aarch64.whl";
    public static final String SHA256 = "2519dce230b3ed13a93a3efed15c6b8b163aeacd77859ddf59eef3faa69c635d";
    public static final long ARCHIVE_SIZE = 33195566L;
    private static final long MAX_EXPANDED = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 3000;
    private static final String MARKER = ".bloco-java";
    private static final String VERIFIED = ".bloco-java-verificado";
    private JavaRuntimeInstaller() { }

    private static File home(File base) { return new File(base, "java"); }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Não foi possível criar a pasta do Java.");
    }
    private static boolean regular(File file) {
        return file.isFile() && !Files.isSymbolicLink(file.toPath());
    }
    public static boolean installed(File base) {
        File dir = home(base);
        try {
            return !Files.isSymbolicLink(dir.toPath()) && regular(new File(dir, MARKER)) &&
                JAVA_ID.equals(RuntimeInstaller.readText(new File(dir, MARKER), 256).trim()) &&
                regular(new File(dir, "bin/java")) && new File(dir, "bin/java").canExecute() &&
                regular(new File(dir, "lib/modules")) && regular(new File(dir, "lib/libjava.so")) &&
                regular(new File(dir, "lib/libjli.so")) && regular(new File(dir, "lib/server/libjvm.so")) &&
                regular(new File(dir, "legal/java.base/LICENSE"));
        } catch (Exception ignored) { return false; }
    }
    private static String verificationId(File runtime) throws IOException {
        return JAVA_ID + "\n" + RuntimeInstaller.readText(new File(runtime, ".bloco-runtime"), 256).trim();
    }
    public static boolean verified(File base, File runtime) {
        try {
            File marker = new File(home(base), VERIFIED);
            return installed(base) && regular(marker) && verificationId(runtime).equals(RuntimeInstaller.readText(marker, 512).trim());
        } catch (Exception ignored) { return false; }
    }
    private static void cancelled(Progress progress) throws IOException {
        if (progress != null && progress.cancelled()) throw new IOException("Instalação do Java cancelada.");
    }
    private static void message(Progress progress, String text) { if (progress != null) progress.message(text); }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static void checkDigest(File archive, long expectedSize, String expectedHash, Progress progress) throws Exception {
        if (!archive.isFile() || archive.length() != expectedSize) throw new IOException("Download do Java incompleto.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
            byte[] data = new byte[65536]; int count;
            while ((count = in.read(data)) != -1) { cancelled(progress); digest.update(data, 0, count); }
        }
        if (!MessageDigest.isEqual(hex(digest.digest()).getBytes(StandardCharsets.US_ASCII), expectedHash.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("A verificação SHA-256 do Java falhou; nada foi instalado.");
    }
    private static void download(File target, Progress progress) throws Exception {
        URL url = new URL(URL);
        if (!"https".equals(url.getProtocol()) || !"files.pythonhosted.org".equals(url.getHost()) || url.getUserInfo() != null)
            throw new IOException("Endereço do Java inválido.");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(30000); connection.setReadTimeout(90000);
        connection.setRequestProperty("User-Agent", "Bloco-Local/3 (Android)");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("O servidor do Java respondeu HTTP " + code + ".");
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != ARCHIVE_SIZE) throw new IOException("O tamanho do Java é diferente do esperado.");
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream file = new FileOutputStream(target);
                 BufferedOutputStream out = new BufferedOutputStream(file)) {
                byte[] data = new byte[65536]; int count; long total = 0, last = 0;
                while ((count = in.read(data)) != -1) {
                    cancelled(progress); total += count;
                    if (total > ARCHIVE_SIZE) throw new IOException("Download do Java maior que o esperado.");
                    out.write(data, 0, count);
                    if (total - last >= 4L * 1024 * 1024) {
                        last = total;
                        message(progress, "Baixando Java 21: " + (total / 1024 / 1024) + " de " + (ARCHIVE_SIZE / 1024 / 1024) + " MB…");
                    }
                }
                out.flush(); file.getFD().sync();
                if (total != ARCHIVE_SIZE) throw new IOException("Download do Java incompleto.");
            }
        } finally { connection.disconnect(); }
    }
    private static void recover(File base) throws IOException {
        File current = home(base), previous = new File(base, ".java-anterior");
        if (!current.exists() && previous.exists()) {
            if (!previous.isDirectory() || Files.isSymbolicLink(previous.toPath()) || !previous.renameTo(current))
                throw new IOException("Não foi possível recuperar a instalação anterior do Java.");
        }
    }
    /** Downloads only if the pinned runtime is not already installed. */
    public static File install(File base, Progress progress) throws Exception {
        mkdir(base); recover(base);
        if (installed(base)) return home(base);
        File archive = new File(base, ".java-download.parcial");
        try {
            message(progress, "Baixando o Java ARM64 para o Ubuntu…");
            download(archive, progress);
            message(progress, "Verificando e instalando o Java…");
            return installArchive(base, archive, ARCHIVE_SIZE, SHA256, progress);
        } finally { if (archive.exists() && !archive.delete()) archive.deleteOnExit(); }
    }
    /** Package-private so tests can exercise malicious/truncated archives with a test digest. */
    static File installArchive(File base, File archive, long expectedSize, String expectedHash, Progress progress) throws Exception {
        mkdir(base); recover(base);
        checkDigest(archive, expectedSize, expectedHash, progress);
        File current = home(base), previous = new File(base, ".java-anterior"), stage = new File(base, ".java-novo");
        if (stage.exists()) SafeZip.deleteTree(stage);
        if (!stage.mkdir()) throw new IOException("Não foi possível preparar a instalação do Java.");
        try {
            SafeZip.extract(new FileInputStream(archive), stage, new SafeZip.Budget(MAX_EXPANDED, MAX_ENTRIES));
            cancelled(progress);
            File candidate = new File(stage, "jdk4py/java-runtime");
            String release = RuntimeInstaller.readText(new File(candidate, "release"), 4096);
            if (!release.contains("JAVA_VERSION=\"" + JAVA_VERSION + "\"")) throw new IOException("Versão inesperada do Java.");
            for (String required : new String[]{"bin/java", "lib/modules", "lib/libjava.so", "lib/libjli.so", "lib/server/libjvm.so", "legal/java.base/LICENSE"}) {
                if (!regular(new File(candidate, required))) throw new IOException("Java incompleto: " + required);
            }
            byte[] header = new byte[20];
            try (InputStream in = new FileInputStream(new File(candidate, "bin/java"))) {
                if (in.read(header) != header.length || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' ||
                    header[3] != 'F' || header[4] != 2 || header[5] != 1 || (header[18] & 255) != 183 || header[19] != 0)
                    throw new IOException("O Java não é o executável Linux ARM64 esperado.");
            }
            File bin = new File(candidate, "bin");
            File[] commands = bin.listFiles();
            if (commands == null) throw new IOException("Executáveis do Java ausentes.");
            for (File command : commands) {
                if (!regular(command) || !command.setExecutable(true, true)) throw new IOException("Não foi possível preparar o Java.");
            }
            for (String helper : new String[]{"lib/jspawnhelper", "lib/jexec"}) {
                File file = new File(candidate, helper);
                if (file.exists() && (!regular(file) || !file.setExecutable(true, true))) throw new IOException("Não foi possível preparar o Java.");
            }
            RuntimeInstaller.writeText(new File(candidate, MARKER), JAVA_ID + "\n");
            if (previous.exists()) SafeZip.deleteTree(previous);
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Não foi possível preservar o Java anterior.");
            if (!candidate.renameTo(current)) {
                if (previous.exists()) previous.renameTo(current);
                throw new IOException("Não foi possível ativar o Java.");
            }
            if (previous.exists()) SafeZip.deleteTree(previous);
            return current;
        } finally { if (stage.exists()) SafeZip.deleteTree(stage); }
    }
    /** Run java -version inside the Ubuntu environment; a file marker alone is not execution proof. */
    public static String verify(File base, File runtime, Progress progress) throws Exception {
        if (!installed(base)) throw new IOException("Java ainda não está instalado.");
        File marker = new File(home(base), VERIFIED);
        if (marker.exists() && !marker.delete()) throw new IOException("Não foi possível atualizar o estado do Java.");
        message(progress, "Verificando o Java no ambiente Ubuntu…");
        Process process = RuntimeCommand.createJava(base, runtime, "-version").start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = in.read(bytes)) != -1) synchronized (output) {
                    if (output.length() < 12000) output.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) { }
        }, "bloco-java-verificar");
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.currentTimeMillis() + 90000;
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                cancelled(progress);
                if (System.currentTimeMillis() > deadline) throw new IOException("O Java demorou demais para iniciar.");
            }
            reader.join(5000);
            String text; synchronized (output) { text = output.toString().trim(); }
            if (process.exitValue() != 0 || !text.contains("21.0.8"))
                throw new IOException("O Java não iniciou neste ambiente (código " + process.exitValue() + "): " +
                    text.substring(Math.max(0, text.length() - 1200)));
            RuntimeInstaller.writeText(marker, verificationId(runtime) + "\n");
            return text;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
