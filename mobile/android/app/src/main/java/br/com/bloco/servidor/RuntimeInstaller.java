package br.com.bloco.servidor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Installs the signed, bundled ARM64 environment into private application storage. */
public final class RuntimeInstaller {
    private static final String RUNTIME_ID = "ubuntu24-box64-v044-proot-termux-5107102-musl-reuse-cleanup-3";
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private RuntimeInstaller() { }

    public static File install(File base, InputStream asset) throws Exception {
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Não foi possível criar os arquivos do aplicativo.");
        File current = new File(base, "runtime");
        File marker = new File(current, ".bloco-runtime");
        if (marker.isFile() && RUNTIME_ID.equals(readText(marker, 256).trim()) &&
            new File(current, "proot").isFile() && new File(current, "rootfs/opt/box64/box64").isFile() &&
            new File(current, "rootfs/etc/ssl/certs/ca-certificates.crt").isFile()) {
            asset.close();
            return current;
        }
        File stage = new File(base, ".runtime-novo");
        File previous = new File(base, ".runtime-anterior");
        // Recover from an interrupted swap before cleaning a partial extraction.
        if (!current.exists() && previous.exists() && !previous.renameTo(current))
            throw new IOException("Não foi possível recuperar o ambiente anterior.");
        if (stage.exists()) SafeZip.deleteTree(stage);
        if (!stage.mkdir()) throw new IOException("Não foi possível preparar o ambiente.");
        try {
            SafeZip.extract(asset, stage, new SafeZip.Budget(MAX_BYTES, 40000));
            JSONObject manifest = new JSONObject(readText(new File(stage, "runtime-manifest.json"), 4 * 1024 * 1024));
            if (manifest.getInt("format") != 1) throw new IOException("Formato do ambiente não reconhecido.");
            if (manifest.optInt("box64-tcp-reuse", 0) != 1) throw new IOException("Ambiente sem compatibilidade TCP necessária.");
            if (manifest.optInt("box64-cleanup-thread-safety", 0) != 1) throw new IOException("Ambiente sem proteção concorrente necessária.");
            JSONArray links = manifest.getJSONArray("links");
            JSONArray executable = manifest.getJSONArray("executable");
            if (links.length() > 20000 || executable.length() > 20000) throw new IOException("Ambiente inválido.");
            Path root = new File(stage, "rootfs").getAbsoluteFile().toPath().normalize();
            for (int i = 0; i < links.length(); i++) {
                JSONArray link = links.getJSONArray(i);
                String name = link.getString(0), targetName = link.getString(1);
                if (targetName.indexOf('\0') >= 0 || targetName.length() > 2048) throw new IOException("Link inválido no ambiente.");
                File linkFile = SafeZip.child(stage, name);
                Path path = linkFile.getAbsoluteFile().toPath().normalize();
                if (!path.startsWith(root) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Link inseguro no ambiente.");
                Path target = targetName.startsWith("/")
                    ? root.resolve(targetName.substring(1)).normalize()
                    : path.getParent().resolve(targetName).normalize();
                if (!target.startsWith(root)) throw new IOException("Link fora do ambiente.");
                File parent = linkFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível criar link.");
                // Convert absolute rootfs links to safe relative links. They work both
                // directly and inside proot, without ever pointing outside this tree.
                Files.createSymbolicLink(path, path.getParent().relativize(target));
            }
            for (int i = 0; i < executable.length(); i++) {
                File file = SafeZip.child(stage, executable.getString(i));
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || !file.setExecutable(true, true))
                    throw new IOException("Não foi possível preparar um executável do ambiente.");
            }
            if (!new File(stage, "proot").isFile() || !new File(stage, "rootfs/opt/box64/box64").isFile() ||
                !new File(stage, "rootfs/etc/ssl/certs/ca-certificates.crt").isFile())
                throw new IOException("O ambiente está incompleto.");
            writeText(new File(stage, ".bloco-runtime"), RUNTIME_ID + "\n");
            if (previous.exists()) SafeZip.deleteTree(previous);
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Não foi possível preservar o ambiente anterior.");
            if (!stage.renameTo(current)) {
                if (previous.exists()) previous.renameTo(current);
                throw new IOException("Não foi possível ativar o ambiente.");
            }
            if (previous.exists()) SafeZip.deleteTree(previous);
            return current;
        } finally {
            if (stage.exists()) SafeZip.deleteTree(stage);
        }
    }

    static String readText(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            SafeZip.copyLimited(in, out, limit);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível salvar dados.");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
