package br.com.bloco.servidor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Safely imports and exports worlds and Bedrock packs in the phone's private storage. */
public final class LocalFiles {
    public static final long MAX_UPLOAD = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED = 1024L * 1024 * 1024;
    private final File base, store, world;

    public LocalFiles(File base) {
        this.base = base;
        this.store = new File(base, "servidor");
        this.world = new File(store, "worlds/Bloco");
    }
    public File world() { return world; }
    public File store() { return store; }
    public boolean hasWorld() { return new File(world, "level.dat").isFile() && new File(world, "db").isDirectory(); }

    private File operation() throws IOException {
        File dir = new File(base, "operacao-" + UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("Não foi possível criar uma pasta temporária.");
        return dir;
    }
    private File receive(InputStream source, File dir) throws IOException {
        File target = new File(dir, "enviado.zip");
        try (InputStream input = source; OutputStream output = new FileOutputStream(target)) {
            SafeZip.copyLimited(input, output, MAX_UPLOAD);
        }
        return target;
    }
    private static File[] children(File directory) throws IOException {
        File[] list = directory.listFiles();
        if (list == null) throw new IOException("Não foi possível ler os arquivos.");
        return list;
    }
    private static List<File> find(File dir, String wanted) throws IOException {
        List<File> found = new ArrayList<>();
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(dir);
        int count = 0;
        while (!queue.isEmpty()) {
            File file = queue.removeFirst();
            if (++count > 30000) throw new IOException("Arquivos demais no conteúdo.");
            if (file.isDirectory()) {
                if (file.getName().equals("__MACOSX")) continue;
                for (File child : children(file)) queue.addLast(child);
            } else if (file.isFile() && file.getName().equalsIgnoreCase(wanted)) found.add(file);
        }
        return found;
    }
    private static String smallText(File file) throws IOException { return RuntimeInstaller.readText(file, 1024 * 1024); }
    private static final class Pack {
        final String id, kind, name;
        final int[] version;
        final File directory;
        Pack(String id, String kind, String name, int[] version, File directory) {
            this.id = id; this.kind = kind; this.name = name; this.version = version; this.directory = directory;
        }
    }
    private static List<Pack> packs(File directory) throws Exception {
        List<Pack> result = new ArrayList<>();
        for (File file : find(directory, "manifest.json")) {
            JSONObject json;
            try { json = new JSONObject(smallText(file)); }
            catch (JSONException e) { throw new IOException("Um manifesto de conteúdo contém JSON inválido."); }
            JSONObject header = json.optJSONObject("header");
            JSONArray modules = json.optJSONArray("modules");
            JSONArray version = header == null ? null : header.optJSONArray("version");
            String id = header == null ? "" : header.optString("uuid", "").toLowerCase(Locale.ROOT);
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
                    version == null || version.length() != 3 || modules == null) continue;
            int[] v = new int[3];
            for (int i = 0; i < 3; i++) {
                Object n = version.get(i);
                if (!(n instanceof Number) || ((Number) n).doubleValue() != Math.floor(((Number) n).doubleValue()) ||
                        ((Number) n).longValue() < 0 || ((Number) n).longValue() > Integer.MAX_VALUE)
                    throw new IOException("A versão de um pacote é inválida.");
                v[i] = ((Number) n).intValue();
            }
            boolean data = false, resources = false;
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                String type = module == null ? "" : module.optString("type", "");
                data |= "data".equals(type) || "script".equals(type);
                resources |= "resources".equals(type);
            }
            String name = header.optString("name", "Conteúdo").replaceAll("[\\x00-\\x1F\\x7F]", "");
            if (name.length() > 100) name = name.substring(0, 100);
            if (data) result.add(new Pack(id, "behavior", name, v, file.getParentFile()));
            if (resources) result.add(new Pack(id, "resource", name, v, file.getParentFile()));
        }
        return result;
    }
    private static void unique(List<Pack> packs) throws IOException {
        Set<String> keys = new HashSet<>();
        for (Pack p : packs) if (!keys.add(p.kind + ":" + p.id))
            throw new IOException("O arquivo contém dois pacotes com o mesmo identificador.");
    }
    private File currentRelease() throws IOException {
        File current = new File(store, "versao.txt");
        if (!current.isFile()) return null;
        String version = smallText(current).trim();
        if (!version.matches("[0-9]+(?:\\.[0-9]+){2,4}")) throw new IOException("Versão instalada inválida.");
        File release = new File(store, "versoes/" + version);
        return release.isDirectory() ? release : null;
    }
    private static final class Replacement {
        final File source, target;
        File old; boolean installed;
        Replacement(File source, File target) { this.source = source; this.target = target; }
    }
    private void stagePack(List<Replacement> replacements, Pack pack, File op, boolean includeWorld) throws IOException {
        String folder = pack.kind + "_packs";
        String name = "bloco-" + pack.id;
        List<File> targets = new ArrayList<>();
        targets.add(new File(store, folder + "/" + name));
        File release = currentRelease();
        if (release != null) targets.add(new File(release, folder + "/" + name));
        if (includeWorld) targets.add(new File(world, folder + "/" + name));
        for (File target : targets) {
            File staged = new File(op, "pack-" + replacements.size());
            SafeZip.copyTree(pack.directory, staged);
            replacements.add(new Replacement(staged, target));
        }
    }
    private File backupRoot() throws IOException {
        File dir = new File(base, "backups");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não foi possível preservar o mundo anterior.");
        return dir;
    }
    private void commit(List<Replacement> changes, File op, File worldTarget) throws IOException {
        List<Replacement> applied = new ArrayList<>();
        try {
            for (Replacement change : changes) {
                File parent = change.target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível salvar o conteúdo.");
                if (change.target.exists()) {
                    // Preserve a previous world outside the temporary operation
                    // before swapping. Even an abrupt process death cannot delete it.
                    change.old = worldTarget != null && change.target.equals(worldTarget)
                        ? new File(backupRoot(), "mundo-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8))
                        : new File(op, "anterior-" + applied.size());
                    if (!change.target.renameTo(change.old)) throw new IOException("Não foi possível preservar o conteúdo anterior.");
                }
                applied.add(change);
                if (!change.source.renameTo(change.target)) throw new IOException("Não foi possível ativar o conteúdo.");
                change.installed = true;
            }
        } catch (IOException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                Replacement change = applied.get(i);
                try {
                    if (change.installed) SafeZip.deleteTree(change.target);
                    if (change.old != null && !change.old.renameTo(change.target))
                        throw new IOException("O conteúdo anterior ficou em " + change.old.getAbsolutePath());
                } catch (IOException recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
    }

    public String importWorld(InputStream input) throws Exception {
        File op = operation();
        try {
            File archive = receive(input, op);
            File extracted = new File(op, "extraido");
            SafeZip.extract(new FileInputStream(archive), extracted, new SafeZip.Budget(MAX_EXPANDED, 15000));
            List<File> candidates = find(extracted, "level.dat");
            if (candidates.size() != 1) throw new IOException("Escolha um único mundo Bedrock com level.dat.");
            File source = candidates.get(0).getParentFile();
            if (!new File(source, "db").isDirectory()) throw new IOException("O mundo não contém a pasta db.");
            List<Pack> embedded = new ArrayList<>();
            for (String kind : new String[]{"behavior_packs", "resource_packs"}) {
                File folder = new File(source, kind);
                if (folder.isDirectory()) embedded.addAll(packs(folder));
            }
            unique(embedded);
            List<Replacement> changes = new ArrayList<>();
            for (Pack p : embedded) stagePack(changes, p, op, false);
            changes.add(new Replacement(source, world));
            commit(changes, op, world);
            return "Mundo importado" + (embedded.isEmpty() ? "." : " com " + embedded.size() + " conteúdo(s).") + " O mundo anterior foi preservado, se existia.";
        } finally { if (op.exists()) SafeZip.deleteTree(op); }
    }

    public String importPacks(InputStream input) throws Exception {
        if (!hasWorld()) throw new IOException("Crie ou importe um mundo antes de adicionar conteúdos.");
        File op = operation();
        try {
            File upload = receive(input, op);
            File extracted = new File(op, "extraido-0");
            SafeZip.Budget budget = new SafeZip.Budget(MAX_EXPANDED, 15000);
            SafeZip.extract(new FileInputStream(upload), extracted, budget);
            List<File> roots = new ArrayList<>(); roots.add(extracted);
            int nested = 0;
            // .mcaddon containers often hold .mcpack or ZIP files. Cap both
            // nesting and total decompressed bytes across all archives.
            for (int i = 0; i < roots.size(); i++) {
                File root = roots.get(i);
                List<File> archives = new ArrayList<>();
                for (String ext : new String[]{".mcpack", ".zip"}) collectArchives(root, ext, archives);
                for (File archive : archives) {
                    if (++nested > 24) throw new IOException("O arquivo contém pacotes compactados demais.");
                    File nestedDir = new File(op, "extraido-" + nested);
                    SafeZip.extract(new FileInputStream(archive), nestedDir, budget);
                    roots.add(nestedDir);
                }
            }
            List<Pack> discovered = new ArrayList<>();
            for (File root : roots) discovered.addAll(packs(root));
            unique(discovered);
            if (discovered.isEmpty()) throw new IOException("Nenhum add-on ou textura Bedrock válido foi encontrado.");
            List<Replacement> changes = new ArrayList<>();
            JSONArray behavior = packList("behavior"), resource = packList("resource");
            for (Pack p : discovered) {
                stagePack(changes, p, op, true);
                JSONArray list = p.kind.equals("behavior") ? behavior : resource;
                JSONArray next = new JSONArray();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null || !p.id.equalsIgnoreCase(item.optString("pack_id", ""))) next.put(list.get(i));
                }
                JSONObject entry = new JSONObject(); entry.put("pack_id", p.id);
                JSONArray v = new JSONArray(); for (int number : p.version) v.put(number);
                entry.put("version", v); next.put(entry);
                if (p.kind.equals("behavior")) behavior = next; else resource = next;
            }
            for (String kind : new String[]{"behavior", "resource"}) {
                File file = new File(op, "lista-" + kind + ".json");
                RuntimeInstaller.writeText(file, (kind.equals("behavior") ? behavior : resource).toString(2) + "\n");
                changes.add(new Replacement(file, new File(world, "world_" + kind + "_packs.json")));
            }
            commit(changes, op, null);
            return discovered.size() + " conteúdo(s) adicionado(s). Reinicie o servidor para aplicar.";
        } finally { if (op.exists()) SafeZip.deleteTree(op); }
    }
    private JSONArray packList(String kind) throws Exception {
        File path = new File(world, "world_" + kind + "_packs.json");
        if (!path.exists()) return new JSONArray();
        try {
            JSONArray list = new JSONArray(smallText(path));
            if (list.length() > 1000) throw new IOException("A lista de conteúdos é grande demais.");
            return list;
        } catch (JSONException error) { throw new IOException("A lista de conteúdos do mundo está danificada."); }
    }
    private static void collectArchives(File root, String extension, List<File> result) throws IOException {
        ArrayDeque<File> q = new ArrayDeque<>(); q.add(root);
        while (!q.isEmpty()) {
            File file = q.removeFirst();
            if (file.isDirectory()) for (File child : children(file)) q.addLast(child);
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(extension)) result.add(file);
        }
    }
    private File latestBackup() {
        File directory = new File(base, "backups");
        File[] entries = directory.listFiles();
        File best = null; long newest = -1;
        if (entries != null) for (File entry : entries) {
            String name = entry.getName();
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?:mundo|atualizacao)-([0-9]+)(?:-.*|\\.mcworld)").matcher(name);
            if (!match.matches() || !(entry.isDirectory() || (entry.isFile() && name.endsWith(".mcworld")))) continue;
            try {
                long time = Long.parseLong(match.group(1));
                if (time > newest) { newest = time; best = entry; }
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }
    public boolean hasBackup() { return latestBackup() != null; }
    public String restoreLatestBackup() throws Exception {
        File backup = latestBackup();
        if (backup == null) throw new IOException("Ainda não há uma cópia anterior do mundo.");
        if (backup.isFile()) return importWorld(new FileInputStream(backup));
        File temporary = operation();
        try {
            File archive = new File(temporary, "backup.mcworld");
            try (OutputStream out = new FileOutputStream(archive)) { SafeZip.exportWorld(backup, out); }
            return importWorld(new FileInputStream(archive));
        } finally { if (temporary.exists()) SafeZip.deleteTree(temporary); }
    }
    public void exportWorld(OutputStream output) throws IOException { SafeZip.exportWorld(world, output); }
}
