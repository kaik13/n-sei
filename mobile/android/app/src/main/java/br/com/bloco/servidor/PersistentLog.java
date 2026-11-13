package br.com.bloco.servidor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Private, bounded local console history; never sent to an external service. */
public final class PersistentLog {
    private static final Object LOCK = new Object();
    private static final long MAX_FILE = 1024L * 1024;
    private static final int MAX_TAIL = 256 * 1024;
    private final File directory, active, previous;

    public PersistentLog(File base) {
        directory = new File(base, "logs");
        active = new File(directory, "servidor.log");
        previous = new File(directory, "servidor-anterior.log");
    }
    public void append(String value) throws IOException {
        if (value == null) value = "";
        value = value.replace('\r', ' ').replace('\n', ' ').replace('\0', '?');
        if (value.length() > 4000) value = value.substring(0, 4000) + " …";
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        byte[] line = ("[" + time + "] " + value + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível criar o histórico de logs.");
            if (active.isFile() && active.length() + line.length > MAX_FILE) {
                if (previous.exists() && !previous.delete()) throw new IOException("Não foi possível girar os logs.");
                if (!active.renameTo(previous)) throw new IOException("Não foi possível girar os logs.");
            }
            try (FileOutputStream out = new FileOutputStream(active, true)) { out.write(line); }
        }
    }
    private static byte[] readTail(File file, int limit) throws IOException {
        if (!file.isFile() || limit <= 0) return new byte[0];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long offset = Math.max(0, in.length() - limit);
            in.seek(offset);
            byte[] data = new byte[(int) (in.length() - offset)];
            in.readFully(data);
            if (offset == 0) return data;
            // Skip a partial first line (and any partial UTF-8 character).
            int start = 0; while (start < data.length && data[start] != '\n') start++;
            if (start < data.length) start++;
            byte[] result = new byte[data.length - start];
            System.arraycopy(data, start, result, 0, result.length); return result;
        }
    }
    /** Most recent complete lines from both rotations, bounded in bytes. */
    public String tail(int requestedBytes) throws IOException {
        int limit = Math.max(0, Math.min(MAX_TAIL, requestedBytes));
        synchronized (LOCK) {
            byte[] recent = readTail(active, limit);
            // If the active file itself was truncated, do not prepend older
            // lines from before the gap and suggest a continuous history.
            byte[] older = active.length() > limit ? new byte[0] : readTail(previous, Math.max(0, limit - recent.length));
            ByteArrayOutputStream out = new ByteArrayOutputStream(older.length + recent.length);
            out.write(older); out.write(recent);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
