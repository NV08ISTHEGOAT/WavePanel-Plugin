package me.nv08.wavepanel.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * WavePanel's version installer: other server software / another Minecraft version (Paper, Purpur, Folia, Fabric,
 * Velocity...), picked in WavePanel from the official download sites.
 *
 * While the server still runs: the jar is downloaded and checked (checksum, size, it really is a jar), a backup is made
 * if asked, and this WavePanel.jar plus its connection settings are put aside. Then the server stops and saves as
 * usual, and only once it's gone (a shutdown hook) is anything changed: the server folder wiped if asked (WavePanel's
 * own folder and server.properties stay), the new jar put in place of the one the host starts, WavePanel.jar copied
 * into the new software's plugins/ or mods/ with its settings, the EULA accepted if asked. The JVM then exits with the
 * restart code, so the host starts the new software - and WavePanel.jar on it connects again by itself.
 */
final class Installer {
    /** Official download sites only. */
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("fill-data.papermc.io", "fill.papermc.io",
            "api.papermc.io", "api.purpurmc.org", "meta.fabricmc.net", "maven.fabricmc.net", "piston-data.mojang.com",
            "launcher.mojang.com"));
    private static final long MAX_JAR = 512L * 1024 * 1024;
    static final String STAGE = ".wavepanel-install";
    static final String LOG = "wavepanel-install.log";
    private static volatile File logRoot;

    /** What to do once the server has stopped - made while it still runs. */
    static final class Plan {
        File root, stage, serverJar, newJar, wavepanelJar, configCopy, oldDataDir;
        String label, addons, who;
        boolean wipe, eula;
    }

    private final Core core;
    private final File root;

    Installer(Core core, File root) {
        this.core = core;
        this.root = root;
    }

    /** The server jar the host starts ("java ... -jar server.jar"): that file gets the new software. */
    File serverJar() throws IOException {
        String cmd = System.getProperty("sun.java.command", "").trim();
        String first = cmd.isEmpty() ? "" : cmd.split("\\s+")[0];
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            File f = new File(first);
            if (!f.isAbsolute()) f = new File(root, first);
            f = f.getCanonicalFile();
            String r = root.getPath() + File.separator;
            if (f.isFile() && f.getPath().startsWith(r)) return f;
        }
        throw new JobLink.JobError(409, "This server isn't started with java -jar <file>.jar (it runs "
                + (cmd.length() > 80 ? cmd.substring(0, 80) + "..." : cmd) + "), so the installer can't swap it. "
                + "Change the start command in your host's panel to a jar first.");
    }

    static int javaMajor() {
        String v = System.getProperty("java.specification.version", "8");
        if (v.startsWith("1.")) v = v.substring(2);
        try {
            return Integer.parseInt(v.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /** Download + checks + backup, while the server runs. Throws a JobError (shown in WavePanel) when it can't. */
    Plan prepare(Map<String, Object> a, Callable<Map<String, Object>> backup) throws Exception {
        String label = (Json.s(a, "label") + " " + Json.s(a, "version")).trim();
        String url = Json.s(a, "url");
        String addons = Json.s(a, "addons");
        if (!"plugins".equals(addons) && !"mods".equals(addons)) throw new JobLink.JobError(400, "Unknown add-ons folder " + addons);
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))) {
            throw new JobLink.JobError(400, "Not an official download site: " + u.getHost());
        }
        int need = (int) Double.parseDouble(Json.s(a, "javaMin").isEmpty() ? "0" : Json.s(a, "javaMin"));
        int have = javaMajor();
        if (need > have) {
            throw new JobLink.JobError(409, "This server runs Java " + have + ", " + label + " needs Java " + need
                    + " or newer. Change the Java version in your host's panel first (often under Startup / Docker image), then install again.");
        }
        File serverJar = serverJar();

        File stage = new File(root, STAGE);
        if (stage.exists()) deleteTree(stage);
        if (!stage.mkdirs()) throw new JobLink.JobError(500, "Could not create " + STAGE);
        File newJar = new File(stage, "server.jar");
        download(u, newJar, Json.s(a, "sha256"), Json.s(a, "md5"));

        File self = selfJar();
        File wpCopy = new File(stage, "WavePanel.jar");
        Files.copy(self.toPath(), wpCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File cfg = new File(core.p.dataDir(), "config.yml");
        File cfgCopy = new File(stage, "config.yml");
        if (cfg.isFile()) Files.copy(cfg.toPath(), cfgCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

        if (Json.b(a, "backup") && !core.p.isProxy()) {
            core.log.info("Installer: backing up first...");
            backup.call();
        }

        Plan p = new Plan();
        p.root = root;
        p.stage = stage;
        p.serverJar = serverJar;
        p.newJar = newJar;
        p.wavepanelJar = wpCopy;
        p.configCopy = cfgCopy.isFile() ? cfgCopy : null;
        p.oldDataDir = core.p.dataDir().getCanonicalFile();
        p.label = label;
        p.addons = addons;
        p.who = Json.s(a, "who");
        p.wipe = Json.b(a, "wipe");
        p.eula = Json.b(a, "eula");
        return p;
    }

    /** This WavePanel.jar (the file the server loaded it from). */
    private static File selfJar() throws Exception {
        File f = new File(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!f.isFile()) throw new JobLink.JobError(500, "Can't find WavePanel.jar itself (" + f + ")");
        return f;
    }

    private void download(URL u, File to, String sha256, String md5) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", core.userAgent() + " (+https://wavepanel.xyz)");
        int code = c.getResponseCode();
        if (code != 200) throw new JobLink.JobError(502, "The download site answered " + code + " for " + u);
        MessageDigest s256 = MessageDigest.getInstance("SHA-256"), m5 = MessageDigest.getInstance("MD5");
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_JAR) throw new JobLink.JobError(413, "That download is bigger than " + (MAX_JAR >> 20) + " MB");
                s256.update(buf, 0, n);
                m5.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        if (!sha256.isEmpty() && !hex(s256.digest()).equalsIgnoreCase(sha256)) {
            throw new JobLink.JobError(502, "The download is damaged (SHA-256 doesn't match) - try again");
        }
        if (!md5.isEmpty() && !hex(m5.digest()).equalsIgnoreCase(md5)) {
            throw new JobLink.JobError(502, "The download is damaged (MD5 doesn't match) - try again");
        }
        try (InputStream in = new FileInputStream(to)) {
            byte[] head = new byte[4];
            if (in.read(head) != 4 || head[0] != 'P' || head[1] != 'K') throw new JobLink.JobError(502, "The download isn't a jar file");
        }
        core.log.info("Installer: downloaded " + (total >> 10) + " KB from " + u.getHost());
    }

    // ------------------------------------------------------------------ once the server has stopped

    /** Runs in a shutdown hook: waits for the game to finish saving, swaps everything, then exits with {@code code}. */
    static void apply(Plan p, int code) {
        logRoot = p.root;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                // the game's main thread finishes saving - unless it's the one ending the JVM (it waits for us then)
                if ("Server thread".equals(t.getName()) && t != Thread.currentThread() && !exiting(t)) t.join(120_000);
            }
            Thread.sleep(1500);                                      // the other shutdown hooks (logs, saving) finish
            say("Installing " + p.label + "...");
            if (p.wipe) wipe(p);

            File addons = new File(p.root, p.addons);
            if (!addons.isDirectory()) addons.mkdirs();
            File wpJar = new File(addons, "WavePanel.jar");
            Files.copy(p.wavepanelJar.toPath(), wpJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            new File(new File(addons, ".paper-remapped"), "WavePanel.jar").delete();   // Paper's stale remapped copy

            File dataDir = "mods".equals(p.addons) ? new File(new File(p.root, "config"), "wavepanel") : new File(addons, "WavePanel");
            dataDir.mkdirs();
            if (p.configCopy != null) {
                Files.copy(p.configCopy.toPath(), new File(dataDir, "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            File oldBackups = new File(p.oldDataDir, "backups"), newBackups = new File(dataDir, "backups");
            if (oldBackups.isDirectory() && !oldBackups.getCanonicalFile().equals(newBackups.getCanonicalFile()) && !newBackups.exists()) {
                Files.move(oldBackups.toPath(), newBackups.toPath());
            }
            if (p.eula) {
                String when = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
                Files.write(new File(p.root, "eula.txt").toPath(), ("#By changing the setting below to TRUE you are indicating your "
                        + "agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).\n#Accepted in WavePanel"
                        + (p.who.isEmpty() ? "" : " by " + p.who) + " on " + when + "\neula=true\n").getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(p.newJar.toPath(), p.serverJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                deleteTree(p.stage);
            } catch (IOException locked) {
                // Windows: this JVM still holds its own jar open - a little helper swaps it the moment we've exited
                File bin = new File(System.getProperty("java.home"), "bin");
                File exe = new File(bin, "java.exe").isFile() ? new File(bin, "java.exe") : new File(bin, "java");
                say("The server jar is still locked (" + locked.getClass().getSimpleName() + ") - a helper swaps it right after this exits.");
                new ProcessBuilder(exe.getPath(), "-cp", wpJar.getPath(), InstallFinisher.NAME,
                        p.newJar.getPath(), p.serverJar.getPath(), p.stage.getPath())
                        .directory(p.root).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(p.root, LOG))).start();
            }
            say("Installed " + p.label + " - the server starts it now (with WavePanel.jar in " + p.addons + "/).");
        } catch (Throwable t) {
            say("The install didn't finish: " + t + " - the files in " + STAGE + "/ are kept.");
        } finally {
            Runtime.getRuntime().halt(code);
        }
    }

    /** Everything goes - except WavePanel's own folder (connection + backups), server.properties and the start scripts. */
    private static void wipe(Plan p) throws IOException {
        Set<String> keep = new HashSet<>(Arrays.asList(STAGE, LOG, "server.properties", "eula.txt",
                "plugins/WavePanel", "plugins/wavepanel", "config/wavepanel", "wavepanel", rel(p.root, p.serverJar)));
        keep.add(rel(p.root, p.oldDataDir));
        wipe(p.root, p.root, keep);
    }

    private static void wipe(File root, File dir, Set<String> keep) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String r = rel(root, k);
            String name = k.getName().toLowerCase(Locale.ROOT);
            if (keep.contains(r) || (dir.equals(root) && (name.endsWith(".sh") || name.endsWith(".bat") || name.endsWith(".cmd")))) continue;
            boolean partly = false;
            for (String x : keep) if (x.startsWith(r + "/")) partly = true;
            if (partly && k.isDirectory() && !Files.isSymbolicLink(k.toPath())) {
                wipe(root, k, keep);
            } else {
                try {
                    deleteTree(k);
                } catch (IOException e) {
                    say("Couldn't delete " + r + ": " + e.getMessage());
                }
            }
        }
    }

    private static boolean exiting(Thread t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if ("java.lang.Shutdown".equals(e.getClassName()) || ("java.lang.Runtime".equals(e.getClassName()) && "exit".equals(e.getMethodName()))) {
                return true;
            }
        }
        return false;
    }

    private static String rel(File root, File f) {
        String r = root.getPath(), p = f.getPath();
        if (p.equals(r)) return "";
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1).replace(File.separatorChar, '/') : "\0outside";
    }

    private static void deleteTree(File f) throws IOException {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        Files.deleteIfExists(f.toPath());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** The logger (and often the console) are gone by now: the console if it's there, and wavepanel-install.log. */
    private static void say(String msg) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + msg;
        System.out.println("[WavePanel] " + msg);
        System.out.flush();
        if (logRoot == null) return;
        try {
            Files.write(new File(logRoot, LOG).toPath(), (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the console line is all there is then
        }
    }
}
