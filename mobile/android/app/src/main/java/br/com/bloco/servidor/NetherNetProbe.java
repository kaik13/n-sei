package br.com.bloco.servidor;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks only the local NetherNet HTTP status endpoint, not an actual player join. */
public final class NetherNetProbe {
    private static final int MAX_BODY = 65536, MAX_LINE = 8192, MAX_HEADERS = 32768;
    private NetherNetProbe() { }

    static boolean listenerReported(String line, int port) {
        if (line == null || port < 1 || port > 65535) return false;
        return Pattern.compile("\\bAccepting clients on\\s+\\S+:" + port + "(?=\\D|$)").matcher(line).find();
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() > deadline) throw new IOException("Timeout do teste local");
    }
    private static String line(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() <= MAX_LINE) {
            checkDeadline(deadline);
            int b = in.read();
            if (b < 0) throw new EOFException("Cabeçalho HTTP incompleto");
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            out.write(b);
        }
        throw new IOException("Linha HTTP grande demais");
    }
    private static byte[] exact(InputStream in, int length, long deadline) throws IOException {
        byte[] result = new byte[length]; int pos = 0;
        while (pos < length) {
            checkDeadline(deadline);
            int read = in.read(result, pos, length - pos);
            if (read < 0) throw new EOFException("Resposta HTTP incompleta");
            if (read > 0) pos += read;
        }
        return result;
    }
    private static byte[] chunked(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int trailerSize = 0;
        while (true) {
            String size = line(in, deadline).split(";", 2)[0].trim();
            if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Chunk HTTP inválido");
            long length = Long.parseLong(size, 16);
            if (length > MAX_BODY - out.size()) throw new IOException("Resposta HTTP grande demais");
            if (length == 0) {
                String trailer;
                do {
                    trailer = line(in, deadline); trailerSize += trailer.length() + 2;
                    if (trailerSize > MAX_HEADERS) throw new IOException("Trailer HTTP grande demais");
                } while (!trailer.isEmpty());
                return out.toByteArray();
            }
            out.write(exact(in, (int) length, deadline));
            if (!line(in, deadline).isEmpty()) throw new IOException("Chunk HTTP inválido");
        }
    }
    private static byte[] body(InputStream in, int length, boolean chunked, long deadline) throws IOException {
        if (chunked) return chunked(in, deadline);
        if (length >= 0) return exact(in, length, deadline);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        while (true) {
            checkDeadline(deadline);
            int read = in.read(buffer);
            if (read < 0) return out.toByteArray();
            if (out.size() + read > MAX_BODY) throw new IOException("Resposta HTTP grande demais");
            out.write(buffer, 0, read);
        }
    }
    private static boolean validStatus(byte[] body) {
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            Object protocol = json.opt("protocol"), players = json.opt("players"), max = json.opt("maxPlayers");
            Object name = json.opt("name"), version = json.opt("version"), level = json.opt("level");
            return protocol instanceof Number && ((Number) protocol).longValue() > 0 &&
                players instanceof Number && ((Number) players).longValue() >= 0 &&
                max instanceof Number && ((Number) max).longValue() > 0 &&
                name instanceof String && !((String) name).isEmpty() &&
                version instanceof String && !((String) version).isEmpty() && level instanceof String;
        } catch (Exception invalid) { return false; }
    }
    public static boolean responds(int port) {
        if (port < 1 || port > 65535) return false;
        // Raw loopback socket deliberately avoids allowing cleartext HTTP for
        // arbitrary external hosts in Android's network-security policy.
        try (Socket socket = new Socket()) {
            long deadline = System.nanoTime() + 3_000_000_000L;
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1200);
            socket.setSoTimeout(1200);
            String request = "GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1:" + port +
                "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            if (!line(in, deadline).matches("HTTP/1\\.[01] 2[0-9][0-9](?: .*)?")) return false;
            int headerSize = 0, length = -1; boolean json = false, chunked = false;
            while (true) {
                String header = line(in, deadline); headerSize += header.length() + 2;
                if (headerSize > MAX_HEADERS) return false;
                if (header.isEmpty()) break;
                int colon = header.indexOf(':'); if (colon <= 0) return false;
                String key = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                if (key.equals("content-type")) json = value.split(";", 2)[0].trim().equals("application/json");
                if (key.equals("transfer-encoding")) chunked = value.equals("chunked");
                if (key.equals("content-length")) {
                    int parsed = Integer.parseInt(value);
                    if (parsed < 0 || parsed > MAX_BODY || (length >= 0 && length != parsed)) return false;
                    length = parsed;
                }
            }
            return json && validStatus(body(in, length, chunked, deadline));
        } catch (Exception error) { return false; }
    }
}
