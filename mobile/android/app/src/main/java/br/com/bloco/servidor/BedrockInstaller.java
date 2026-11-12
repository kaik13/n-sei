package br.com.bloco.servidor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads the latest official Bedrock distribution without bundling Mojang files. */
public final class BedrockInstaller {
    public interface Progress { void message(String text); boolean cancelled(); }
    private static final String API = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links";
    private static final Pattern VERSION = Pattern.compile("/bedrockdedicatedserver/bin-linux/bedrock-server-([0-9]+(?:\\.[0-9]+){2,4})\\.zip");
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private final File base, store, releases;
    private final LocalFiles files;

    public BedrockInstaller(File base) {
        this.base = base; files = new LocalFiles(base); store = files.store(); releases = new File(store, "versoes");
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Não foi possível criar uma pasta de trabalho.");
    }
    private static String version(String url) throws IOException {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            Matcher m = VERSION.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!"https".equals(uri.getScheme()) || host == null || !host.equalsIgnoreCase("www.minecraft.net") ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443) || !m.matches())
                throw new IOException("Endereço oficial do servidor inválido.");
            return m.group(1);
        } catch (java.net.URISyntaxException e) { throw new IOException("Endereço oficial inválido.", e); }
    }
    private static boolean allowed(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return url.getProtocol().equals("https") && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443) &&
            (host.equals("minecraft.net") || host.endsWith(".minecraft.net") ||
             host.equals("minecraft-services.net") || host.endsWith(".minecraft-services.net") ||
             host.equals("mojang.com") || host.endsWith(".mojang.com") ||
             host.equals("microsoft.com") || host.endsWith(".microsoft.com") ||
             host.equals("azureedge.net") || host.endsWith(".azureedge.net"));
    }
    private static HttpURLConnection connect(String url) throws IOException {
        URL current = new URL(url);
        for (int i = 0; i < 6; i++) {
            if (!allowed(current)) throw new IOException("Redirecionamento fora dos domínios oficiais bloqueado.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(30000); connection.setReadTimeout(90000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Bloco/2.0");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("Redirecionamento inválido.");
                current = new URL(current, location); continue;
            }
            if (code != 200) { connection.disconnect(); throw new IOException("A Mojang respondeu HTTP " + code + "."); }
            return connection;
        }
        throw new IOException("Muitos redirecionamentos ao baixar o servidor.");
    }
    private static String latestUrl() throws Exception {
        HttpURLConnection connection = connect(API);
        try (InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            SafeZip.copyLimited(in, out, 1024 * 1024);
            JSONObject result = new JSONObject(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("result");
            JSONArray links = result.getJSONArray("links");
            String found = null;
            for (int i = 0; i < links.length(); i++) {
                JSONObject link = links.getJSONObject(i);
                if ("serverBedrockLinux".equals(link.optString("downloadType"))) {
                    if (found != null) throw new IOException("Lista de versões oficiais ambígua.");
                    found = link.getString("downloadUrl");
                }
            }
            if (found == null) throw new IOException("A Mojang não informou a versão estável do servidor.");
            version(found);
            return found;
        } finally { connection.disconnect(); }
    }
    private static void download(String url, File target, Progress progress) throws Exception {
        HttpURLConnection connection = connect(url);
        long length = connection.getContentLengthLong();
        if (length > MAX_DOWNLOAD) { connection.disconnect(); throw new IOException("O download oficial é grande demais."); }
        try (InputStream input = new BufferedInputStream(connection.getInputStream()); OutputStream out = new FileOutputStream(target)) {
            byte[] bytes = new byte[65536]; int n; long total = 0, last = 0;
            while ((n = input.read(bytes)) != -1) {
                if (progress.cancelled()) throw new IOException("Instalação cancelada.");
                total += n;
                if (total > MAX_DOWNLOAD) throw new IOException("O download oficial é grande demais.");
                out.write(bytes, 0, n);
                if (total - last >= 4L * 1024 * 1024) {
                    last = total;
                    progress.message("Baixando o servidor oficial: " + (total / 1024 / 1024) + " MB" + (length > 0 ? " de " + (length / 1024 / 1024) + " MB" : ""));
                }
            }
            if (total < 1024 * 1024 || (length >= 0 && total != length)) throw new IOException("O download do servidor está incompleto.");
        } finally { connection.disconnect(); }
    }
    private File installed() throws IOException {
        File marker = new File(store, "versao.txt");
        if (!marker.isFile()) return null;
        String name = RuntimeInstaller.readText(marker, 128).trim();
        if (!name.matches("[0-9]+(?:\\.[0-9]+){2,4}")) throw new IOException("Versão instalada inválida.");
        File result = new File(releases, name);
        return new File(result, "bedrock_server").isFile() ? result : null;
    }
    private static void verifyBedrock(File executable) throws IOException {
        byte[] header = new byte[20];
        try (InputStream in = new FileInputStream(executable)) {
            if (in.read(header) != header.length || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' ||
                header[3] != 'F' || header[4] != 2 || header[18] != 0x3e || header[19] != 0)
                throw new IOException("O servidor baixado não é o executável Linux x86-64 esperado.");
        }
    }
    private static void link(File target, String relative) throws IOException {
        if (Files.isSymbolicLink(target.toPath()) || target.exists()) SafeZip.deleteTree(target);
        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(relative));
    }
    private void prepareRelease(File dir) throws Exception {
        verifyBedrock(new File(dir, "bedrock_server"));
        if (!new File(dir, "bedrock_server").setExecutable(true, true)) throw new IOException("Não foi possível preparar o servidor.");
        mkdir(new File(store, "worlds"));
        link(new File(dir, "worlds"), "../../worlds");
        for (String name : new String[]{"server.properties", "allowlist.json", "permissions.json"}) {
            File shared = new File(store, name), original = new File(dir, name);
            if (!shared.exists()) {
                if (original.isFile()) SafeZip.copyTree(original, shared);
                else RuntimeInstaller.writeText(shared, name.equals("server.properties") ? "" : "[]\n");
            }
            link(original, "../../" + name);
        }
        for (String folder : new String[]{"behavior_packs", "resource_packs"}) {
            File shared = new File(store, folder), dest = new File(dir, folder);
            mkdir(shared); mkdir(dest);
            File[] packs = shared.listFiles();
            if (packs == null) throw new IOException("Não foi possível ler os conteúdos salvos.");
            for (File pack : packs) {
                if (pack.isDirectory() && pack.getName().startsWith("bloco-")) SafeZip.copyTree(pack, new File(dest, pack.getName()));
            }
        }
    }
    private void backupBeforeUpdate(Progress progress) throws IOException {
        if (!files.hasWorld()) return;
        progress.message("Guardando uma cópia do mundo antes de atualizar…");
        File backups = new File(base, "backups"); mkdir(backups);
        File temp = new File(backups, "atualizacao-" + System.currentTimeMillis() + ".mcworld.parcial");
        File complete = new File(backups, temp.getName().replace(".parcial", ""));
        try {
            try (OutputStream out = new FileOutputStream(temp)) { files.exportWorld(out); }
            if (!temp.renameTo(complete)) throw new IOException("Não foi possível guardar a cópia de segurança.");
        } finally { if (temp.exists()) temp.delete(); }
    }
    private void activateVersion(String name) throws IOException {
        File tmp = new File(store, "versao.tmp"), current = new File(store, "versao.txt");
        RuntimeInstaller.writeText(tmp, name + "\n");
        if (!tmp.renameTo(current)) throw new IOException("Não foi possível ativar a versão do servidor.");
    }
    public File prepare(Progress progress) throws Exception {
        mkdir(store); mkdir(releases);
        File existing = installed();
        String url;
        try { progress.message("Verificando a versão estável mais recente da Mojang…"); url = latestUrl(); }
        catch (Exception error) {
            if (existing == null || progress.cancelled()) throw new IOException("Não foi possível verificar a versão oficial. Confira a internet e tente novamente.", error);
            progress.message("Sem acesso à atualização: usando " + existing.getName() + ".");
            return existing;
        }
        String name = version(url);
        if (existing != null && existing.getName().equals(name)) return existing;
        if (progress.cancelled()) throw new IOException("Início cancelado.");
        File release = new File(releases, name);
        if (release.isDirectory() && new File(release, "bedrock_server").isFile()) {
            // Version previously installed: preserve world before changing engines.
            backupBeforeUpdate(progress); activateVersion(name); return release;
        }
        File work = new File(base, "instalacao-" + java.util.UUID.randomUUID()); mkdir(work);
        try {
            File zip = new File(work, "bedrock.zip");
            progress.message("Baixando diretamente da Mojang…"); download(url, zip, progress);
            if (progress.cancelled()) throw new IOException("Início cancelado.");
            progress.message("Instalando o servidor " + name + " no celular…");
            File stage = new File(work, "release");
            SafeZip.extract(new FileInputStream(zip), stage, new SafeZip.Budget(2L * 1024 * 1024 * 1024, 50000));
            prepareRelease(stage);
            if (progress.cancelled()) throw new IOException("Início cancelado.");
            backupBeforeUpdate(progress);
            if (release.exists()) SafeZip.deleteTree(release);
            if (!stage.renameTo(release)) throw new IOException("Não foi possível instalar a versão do servidor.");
            activateVersion(name);
            return release;
        } finally { if (work.exists()) SafeZip.deleteTree(work); }
    }
    public void configure(String name, int slots, boolean authenticated, String mode, String difficulty) throws IOException {
        if (name == null) name = "Meu servidor";
        name = name.replaceAll("[\\r\\n\\x00-\\x1f\\x7f]", "").trim();
        if (name.length() < 2) name = "Meu servidor";
        if (name.length() > 40) name = name.substring(0, 40);
        slots = Math.max(10, Math.min(30, slots));
        if (!"creative".equals(mode) && !"adventure".equals(mode)) mode = "survival";
        if (!"peaceful".equals(difficulty) && !"easy".equals(difficulty) && !"hard".equals(difficulty)) difficulty = "normal";
        File props = new File(store, "server.properties");
        String content = props.isFile() ? RuntimeInstaller.readText(props, 1024 * 1024) : "";
        String[][] settings = {
            {"server-name", name}, {"level-name", "Bloco"}, {"server-port", "19132"}, {"server-portv6", "19133"},
            {"max-players", Integer.toString(slots)}, {"online-mode", authenticated ? "true" : "false"},
            {"allow-list", "false"}, {"transport", "raknet"}, {"view-distance", "5"}, {"tick-distance", "4"},
            {"gamemode", mode}, {"difficulty", difficulty},
        };
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) if (!line.isEmpty()) lines.add(line);
        for (String[] setting : settings) {
            boolean present = false;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(setting[0] + "=")) {
                lines.set(i, setting[0] + "=" + setting[1]); present = true;
            }
            if (!present) lines.add(setting[0] + "=" + setting[1]);
        }
        File temporary = new File(store, "server.properties.tmp");
        RuntimeInstaller.writeText(temporary, String.join("\n", lines) + "\n");
        if (!temporary.renameTo(props)) throw new IOException("Não foi possível salvar a configuração.");
    }
}
