package br.com.bloco.servidor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The same two process profiles are exercised on real ARM64 hardware in CI. */
public final class RuntimeCommand {
    private RuntimeCommand() { }
    public static ProcessBuilder create(File base, File runtime, File release, boolean direct) throws IOException {
        return create(base, runtime, release, direct, false);
    }
    public static ProcessBuilder create(File base, File runtime, File release, boolean direct, boolean netherNet) throws IOException {
        File root = new File(runtime, "rootfs");
        File arm = new File(root, "usr/lib/aarch64-linux-gnu"), x86 = new File(root, "usr/lib/box64-x86_64-linux-gnu");
        File box = new File(root, "opt/box64/box64");
        List<String> command = new ArrayList<>();
        File work;
        if (direct) {
            File loader = new File(arm, "ld-linux-aarch64.so.1");
            if (!loader.isFile()) loader = new File(root, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
            String libraries = arm + ":" + new File(root, "lib/aarch64-linux-gnu") + ":" + x86 + ":" + release;
            command.add(loader.toString()); command.add("--library-path"); command.add(libraries);
            command.add(box.toString()); command.add("./bedrock_server"); work = release;
        } else {
            // Use explicit binds rather than -R: the latter masks the guest's
            // CA bundle/resolver with Android's different /etc files. Keep the
            // server and world in the original persistent application store.
            command.add(new File(runtime, "proot").toString()); command.add("--kill-on-exit");
            command.add("-r"); command.add(root.toString());
            for (String system : new String[]{"/dev", "/proc", "/sys"}) {
                command.add("-b"); command.add(system);
            }
            command.add("-b"); command.add(new File(base, "servidor") + ":/srv/bloco");
            command.add("-w"); command.add("/srv/bloco/versoes/" + release.getName());
            command.add("/bin/sh"); command.add("-c");
            command.add("export HOME=/tmp PATH=/usr/bin:/bin LD_LIBRARY_PATH=.:/usr/lib/box64-x86_64-linux-gnu; exec /opt/box64/box64 ./bedrock_server");
            work = base;
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(work).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("LD_PRELOAD"); env.put("HOME", base.toString()); env.put("TERM", "dumb");
        // The bundled Box64 enables TCP SO_REUSEADDR only for this Bedrock
        // process when NetherNet is in use; UDP and SO_REUSEPORT stay unchanged.
        if (netherNet) env.put("BLOCO_REUSE_TCP", "1"); else env.remove("BLOCO_REUSE_TCP");
        env.put("BOX64_LOG", "1"); // include missing-library errors in the in-app console
        File tmp = new File(base, "tmp"); if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("Não foi possível criar uma pasta temporária.");
        env.put("TMPDIR", direct ? tmp.toString() : "/tmp"); env.put("PROOT_TMP_DIR", tmp.toString());
        // Avoid glibc's early rseq registration. Termux PRoot also handles
        // other SIGSYS traps; PROOT_NO_SECCOMP only disables its own filter,
        // never Android's inherited security policy.
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        env.put("PROOT_NO_SECCOMP", "1");
        File certificates = new File(root, "etc/ssl/certs/ca-certificates.crt");
        if (!certificates.isFile()) throw new IOException("O ambiente não contém os certificados necessários.");
        String bundle = direct ? certificates.toString() : "/etc/ssl/certs/ca-certificates.crt";
        env.put("SSL_CERT_FILE", bundle); env.put("CURL_CA_BUNDLE", bundle);
        if (direct) {
            env.put("LD_LIBRARY_PATH", arm + ":" + new File(root, "lib/aarch64-linux-gnu") + ":" + x86 + ":" + release);
            env.put("BOX64_LD_LIBRARY_PATH", x86 + ":" + release);
        } else { env.remove("LD_LIBRARY_PATH"); env.remove("BOX64_LD_LIBRARY_PATH"); }
        return builder;
    }
}
