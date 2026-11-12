package br.com.bloco.servidor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming ZIP operations. Imports never follow links or escape their staging directory. */
public final class SafeZip {
    private static final int BUFFER = 65536;
    private SafeZip() { }

    public static final class Budget {
        public final long maxBytes;
        public final int maxEntries;
        public long bytes;
        public int entries;
        public Budget(long maxBytes, int maxEntries) {
            if (maxBytes < 1 || maxEntries < 1) throw new IllegalArgumentException();
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
        }
        void consume(int count) throws IOException {
            bytes += count;
            if (bytes > maxBytes) throw new IOException("O arquivo descompactado excede o limite permitido.");
        }
        void entry() throws IOException {
            if (++entries > maxEntries) throw new IOException("O arquivo contém entradas demais.");
        }
    }

    static String cleanName(String raw) throws IOException {
        if (raw == null || raw.length() == 0 || raw.length() > 2048 || raw.startsWith("/") || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0)
            throw new IOException("Caminho inválido dentro do arquivo.");
        StringBuilder normalized = new StringBuilder();
        int depth = 0;
        for (String part : raw.split("/")) {
            if (part.length() == 0 || part.equals(".")) continue;
            if (part.equals("..") || (depth == 0 && part.matches("[A-Za-z]:.*")) || ++depth > 64)
                throw new IOException("Caminho inseguro dentro do arquivo: " + raw.replaceAll("[\\x00-\\x1F\\x7F]", "?").substring(0, Math.min(raw.length(), 160)));
            if (normalized.length() != 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.toString();
    }

    static File child(File root, String name) throws IOException {
        String cleaned = cleanName(name);
        File file = new File(root, cleaned);
        String base = root.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator))
            throw new IOException("O arquivo tenta sair da pasta permitida.");
        return file;
    }

    public static void extract(InputStream source, File dest, Budget budget) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Não foi possível criar a pasta temporária.");
        Set<String> files = new HashSet<>();
        boolean any = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                budget.entry();
                String name = cleanName(entry.getName());
                if (name.isEmpty()) { zip.closeEntry(); continue; }
                File file = child(dest, name);
                if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Pasta inválida no ZIP.");
                } else {
                    if (!files.add(name) || file.exists()) throw new IOException("Arquivos duplicados no ZIP.");
                    File parent = file.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível criar uma pasta do ZIP.");
                    if (!file.createNewFile()) throw new IOException("Arquivos duplicados no ZIP.");
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            budget.consume(count);
                            out.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry(); // Java validates the CRC when an entry is completely read.
            }
        }
        if (!any) throw new IOException("O arquivo ZIP está vazio ou inválido.");
    }

    public static long copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        int n;
        while ((n = input.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IOException("O arquivo é grande demais.");
            output.write(buffer, 0, n);
        }
        return total;
    }

    public static void copyTree(File from, File to) throws IOException {
        if (Files.isSymbolicLink(from.toPath())) throw new IOException("Links não são aceitos em conteúdos importados.");
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Não foi possível copiar a pasta.");
            File[] children = from.listFiles();
            if (children == null) throw new IOException("Não foi possível ler a pasta.");
            for (File child : children) copyTree(child, new File(to, child.getName()));
        } else if (from.isFile()) {
            File parent = to.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível criar uma pasta.");
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                copyLimited(in, out, Long.MAX_VALUE);
            }
        } else throw new IOException("Arquivo inesperado.");
    }

    public static void deleteTree(File path) throws IOException {
        if (Files.isSymbolicLink(path.toPath())) {
            if (!path.delete()) throw new IOException("Não foi possível excluir o link.");
            return;
        }
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children == null) throw new IOException("Não foi possível limpar a pasta.");
            for (File file : children) deleteTree(file);
        }
        if (path.exists() && !path.delete()) throw new IOException("Não foi possível limpar um arquivo.");
    }

    /** Writes the world's files at the archive root, as expected by .mcworld. */
    public static void exportWorld(File world, OutputStream destination) throws IOException {
        if (!new File(world, "level.dat").isFile() || !new File(world, "db").isDirectory())
            throw new IOException("Não há mundo completo para exportar.");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(world);
            String root = world.getCanonicalPath();
            while (!stack.isEmpty()) {
                File item = stack.pop();
                if (Files.isSymbolicLink(item.toPath())) throw new IOException("Links no mundo não podem ser exportados.");
                if (item.isDirectory()) {
                    File[] children = item.listFiles();
                    if (children == null) throw new IOException("Não foi possível ler o mundo.");
                    for (File child : children) stack.push(child);
                } else if (item.isFile()) {
                    String absolute = item.getCanonicalPath();
                    if (!absolute.startsWith(root + File.separator)) throw new IOException("Arquivo inesperado no mundo.");
                    String name = absolute.substring(root.length() + 1).replace(File.separatorChar, '/');
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream in = new BufferedInputStream(new FileInputStream(item))) {
                        copyLimited(in, zip, Long.MAX_VALUE);
                    }
                    zip.closeEntry();
                } else throw new IOException("Arquivo inesperado no mundo.");
            }
        }
    }
}
