package me.nv08.wavepanel.core;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The panel's hands on this machine: file manager, plugins/mods and backups.
 *
 * It long-polls WavePanel for jobs (GET .../jobs - the panel holds the request until there is work), does each
 * one on a worker thread and posts the result back. Downloads and uploads stream straight through
 * (.../jobs/&lt;jid&gt;/stream), so a big world backup never sits in memory.
 *
 * Everything is confined to this server's folder: every path is resolved and checked on its real (canonical)
 * location, so "../" and symlinks can't reach anything outside it. Turn it all off with remote.allow-files: false.
 */
final class JobLink implements Runnable {
    private static final long TEXT_LIMIT = 3L * 1024 * 1024;
    private static final String DISABLED = "_removed";               // the panel's "disabled plugins/mods" folder
    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(".yml", ".yaml", ".json", ".json5", ".properties", ".txt",
            ".toml", ".conf", ".cfg", ".log", ".sk", ".md", ".sh", ".ini", ".csv", ".xml", ".html", ".css", ".js", ".lang",
            ".secret", ".mcmeta", ".env", ".bat", ".snbt"));
    private static final List<String> BACKUP_SKIP = Arrays.asList("logs", "cache", "libraries", "versions", "crash-reports",
            "plugins/.paper-remapped", "plugins/spark/tmp", ".fabric", "config/wavepanel/backups", "wavepanel/backups");

    private final Core core;
    private final String jobsUrl;
    private final String secret;
    private final SSLContext pinned;
    private final File root;
    private final File backups;
    private final int keep;
    private final ExecutorService work = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "WavePanel-job");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    JobLink(Core core, String linkUrl, String secret, SSLContext pinned, int keep) throws IOException {
        this.core = core;
        this.jobsUrl = linkUrl.replaceAll("/+$", "") + "/jobs";
        this.secret = secret;
        this.pinned = pinned;
        this.root = new File(".").getCanonicalFile();
        this.backups = new File(core.p.dataDir(), "backups").getCanonicalFile();
        this.keep = Math.max(1, keep);
    }

    void start() {
        Thread t = new Thread(this, "WavePanel-jobs");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        work.shutdownNow();
    }

    @Override
    public void run() {
        int fails = 0;
        while (running) {
            try {
                HttpURLConnection c = open(jobsUrl, "GET");
                c.setReadTimeout(45000);                                  // the panel answers within ~25 s
                int code = c.getResponseCode();
                String body = RemoteLink.read(code < 400 ? c.getInputStream() : c.getErrorStream());
                if (code != 200) throw new IOException("jobs: HTTP " + code);
                fails = 0;
                for (Object o : Json.arr(Json.obj(Json.parse(body)).get("jobs"))) {
                    final Map<String, Object> job = Json.obj(o);
                    work.submit(() -> handle(job));
                }
            } catch (Exception e) {
                if (!running) return;
                fails++;
                try {
                    Thread.sleep(Math.min(30000L, 1000L << Math.min(fails, 5)));
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ jobs

    private void handle(Map<String, Object> job) {
        String jid = Json.s(job, "jid");
        String op = Json.s(job, "op");
        Map<String, Object> a = Json.obj(job.get("args"));
        try {
            Map<String, Object> result;
            switch (op) {
                case "list": result = list(Json.s(a, "path")); break;
                case "read": result = readText(Json.s(a, "path")); break;
                case "write": result = writeText(Json.s(a, "path"), Json.s(a, "content")); break;
                case "mkdir": result = mkdir(Json.s(a, "path")); break;
                case "rename": result = rename(Json.s(a, "from"), Json.s(a, "to")); break;
                case "delete": result = delete(Json.arr(a.get("paths"))); break;
                case "download": download(jid, resolve(Json.s(a, "path"), true)); return;   // the stream IS the answer
                case "upload": result = upload(jid, Json.s(a, "dir"), Json.s(a, "name"), Json.b(a, "plugin")); break;
                case "plugins.list": result = pluginList(); break;
                case "plugins.toggle": result = pluginToggle(Json.s(a, "name"), Json.b(a, "enable")); break;
                case "plugins.delete": result = pluginDelete(Json.s(a, "name"), Json.b(a, "enabled")); break;
                case "backup.list": result = backupList(); break;
                case "backup.create": result = backupCreate(); break;
                case "backup.delete": result = backupDelete(Json.s(a, "name")); break;
                case "backup.download": download(jid, backupFile(Json.s(a, "name"))); return;
                case "server.install": {
                    Installer.Plan plan = new Installer(core, root).prepare(a, this::backupCreate);
                    reply(jid, true, obj("label", plan.label, "wipe", plan.wipe, "java", Installer.javaMajor()), null, 200);
                    core.installAndRestart(plan);                    // answered first: the server stops right after
                    return;
                }
                default: throw new JobError(400, "Unknown job " + op);
            }
            reply(jid, true, result, null, 200);
        } catch (JobError e) {
            reply(jid, false, null, e.getMessage(), e.status);
        } catch (Exception e) {
            reply(jid, false, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 500);
        }
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private Map<String, Object> list(String rel) throws IOException {
        File dir = resolve(rel, true);
        if (!dir.isDirectory()) throw new JobError(400, "Not a folder");
        File[] kids = dir.listFiles();
        List<File> sorted = kids == null ? new ArrayList<File>() : new ArrayList<>(Arrays.asList(kids));
        sorted.sort(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        List<Object> entries = new ArrayList<>();
        for (File f : sorted) {
            boolean link = Files.isSymbolicLink(f.toPath());
            entries.add(obj("name", f.getName(), "type", f.isDirectory() && !link ? "dir" : link ? "link" : "file",
                    "size", f.isDirectory() ? null : (Object) f.length(), "mtime", f.lastModified()));
        }
        return obj("path", rel(dir), "entries", entries);
    }

    private Map<String, Object> readText(String rel) throws IOException {
        File f = resolve(rel, true);
        if (!f.isFile()) throw new JobError(400, "Not a file");
        if (f.length() > TEXT_LIMIT) throw new JobError(413, "File is too big to edit here - download it instead");
        byte[] buf = Files.readAllBytes(f.toPath());
        String name = f.getName().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        if (!TEXT_EXT.contains(ext)) {
            for (int i = 0; i < Math.min(buf.length, 8192); i++) {
                if (buf[i] == 0) throw new JobError(415, "Binary file - download it instead");
            }
        }
        return obj("path", rel(f), "content", new String(buf, StandardCharsets.UTF_8), "size", f.length(), "mtime", f.lastModified());
    }

    private Map<String, Object> writeText(String rel, String content) throws IOException {
        File f = resolve(rel, false);
        File tmp = new File(f.getParentFile(), f.getName() + ".wp-" + UUID.randomUUID().toString().substring(0, 8));
        Files.write(tmp.toPath(), (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);   // a crash mid-save never leaves half a config
        return obj("path", rel(f));
    }

    private Map<String, Object> mkdir(String rel) throws IOException {
        File d = resolve(rel, false);
        if (d.exists()) throw new JobError(409, "Something with that name already exists");
        if (!d.mkdir()) throw new JobError(500, "Could not create the folder");
        return obj("path", rel(d));
    }

    private Map<String, Object> rename(String fromRel, String toRel) throws IOException {
        File from = resolve(fromRel, true);
        File to = resolve(toRel, false);
        if (from.equals(root)) throw new JobError(400, "Cannot move the server folder itself");
        if (to.exists()) throw new JobError(409, "Something with that name already exists");
        Files.move(from.toPath(), to.toPath());
        return obj("path", rel(to));
    }

    private Map<String, Object> delete(List<Object> paths) throws IOException {
        List<Object> done = new ArrayList<>();
        for (Object p : paths) {
            File f = resolve(String.valueOf(p), true);
            if (f.equals(root)) throw new JobError(400, "Refusing to delete the whole server folder");
            deleteTree(f);
            done.add(rel(f));
        }
        return obj("removed", done);
    }

    /** Streams the file to WavePanel (which pipes it on to your browser). */
    private void download(String jid, File f) throws IOException {
        if (!f.isFile()) throw new JobError(400, "Not a file");
        HttpURLConnection c = open(jobsUrl + "/" + jid + "/stream", "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(f.length());
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Name", f.getName().replaceAll("[^\\w .()\\-]", "_"));
        c.setReadTimeout(60000);
        try (InputStream in = new FileInputStream(f); OutputStream out = c.getOutputStream()) {
            copy(in, out);
        }
        RemoteLink.read(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream());
    }

    /** Pulls your upload from WavePanel straight to a temp file, then moves it into place. */
    private Map<String, Object> upload(String jid, String dirRel, String name, boolean addon) throws IOException {
        String safe = new File(name == null ? "" : name).getName().replaceAll("[\\x00/\\\\]", "");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) throw new JobError(400, "Bad file name");
        File dir = addon ? addonsDir(true) : resolve(dirRel, true);
        if (!dir.isDirectory()) throw new JobError(400, "Not a folder");
        File target = resolve(rel(dir).isEmpty() ? safe : rel(dir) + "/" + safe, false);
        File tmp = new File(dir, safe + ".wp-upload-" + UUID.randomUUID().toString().substring(0, 8));
        HttpURLConnection c = open(jobsUrl + "/" + jid + "/stream", "GET");
        c.setReadTimeout(120000);
        int code = c.getResponseCode();
        if (code != 200) throw new JobError(502, "Upload stream: HTTP " + code);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (addon) new File(new File(root, "plugins/.paper-remapped"), safe).delete();   // Paper's stale remapped copy
        return obj("path", rel(target));
    }

    /** plugins/ or mods/ - whichever this kind of server loads its add-ons from. */
    private File addonsDir(boolean create) throws IOException {
        String folder = core.p.addonsFolder();
        if (folder == null) throw new JobError(400, "This kind of server has no plugins or mods folder");
        File dir = new File(root, folder);
        if (create && !dir.isDirectory() && !dir.mkdirs()) throw new JobError(500, "Could not create " + folder);
        return dir;
    }

    private Map<String, Object> pluginList() throws IOException {
        List<Object> out = new ArrayList<>();
        if (core.p.addonsFolder() != null) {
            File dir = addonsDir(false);
            scanJars(dir, true, out);
            scanJars(new File(dir, DISABLED), false, out);
        }
        return obj("plugins", out, "folder", core.p.addonsFolder());
    }

    private static void scanJars(File dir, boolean enabled, List<Object> out) {
        File[] jars = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return;
        Arrays.sort(jars, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File f : jars) {
            if (!f.isFile()) continue;
            out.add(obj("name", f.getName(), "enabled", enabled, "size", f.length(), "mtime", f.lastModified()));
        }
    }

    private Map<String, Object> pluginToggle(String name, boolean enable) throws IOException {
        String n = jar(name);
        File dir = addonsDir(true);
        File on = new File(dir, n), off = new File(new File(dir, DISABLED), n);
        off.getParentFile().mkdirs();
        if (enable) Files.move(off.toPath(), on.toPath());
        else Files.move(on.toPath(), off.toPath());
        new File(new File(dir, ".paper-remapped"), n).delete();
        return obj();
    }

    private Map<String, Object> pluginDelete(String name, boolean enabled) throws IOException {
        String n = jar(name);
        File dir = addonsDir(false);
        File f = enabled ? new File(dir, n) : new File(new File(dir, DISABLED), n);
        Files.delete(f.toPath());
        new File(new File(dir, ".paper-remapped"), n).delete();
        return obj();
    }

    private Map<String, Object> backupList() {
        List<Object> out = new ArrayList<>();
        File[] zips = backups.listFiles((d, n) -> n.endsWith(".zip"));
        if (zips != null) {
            Arrays.sort(zips, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : zips) out.add(obj("name", f.getName(), "size", f.length(), "mtime", f.lastModified()));
        }
        return obj("backups", out, "keep", keep);
    }

    /** Zips the server folder (worlds frozen on disk while it copies) into this plugin's backups/. */
    private Map<String, Object> backupCreate() throws Exception {
        if (!backups.isDirectory() && !backups.mkdirs()) throw new JobError(500, "Could not create " + backups);
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
        File out = new File(backups, root.getName().replaceAll("[^\\w.-]", "_") + "-" + stamp + ".zip");
        File tmp = new File(backups, out.getName() + ".part");
        boolean worlds = !core.p.isProxy();                  // a proxy has no worlds to freeze
        if (worlds) {
            core.consoleAndWait("save-off");
            core.consoleAndWait("save-all flush");
            Thread.sleep(4000);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            zipTree(root, zip);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        } finally {
            if (worlds) core.consoleAndWait("save-on");
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File[] zips = backups.listFiles((d, n) -> n.endsWith(".zip"));
        if (zips != null && zips.length > keep) {
            Arrays.sort(zips, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = keep; i < zips.length; i++) zips[i].delete();
        }
        return obj("name", out.getName(), "size", out.length());
    }

    private void zipTree(File dir, ZipOutputStream zip) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        byte[] buf = new byte[65536];
        for (File f : kids) {
            String rel = rel(f);
            if (f.equals(backups) || BACKUP_SKIP.contains(rel) || f.getName().endsWith(".part")) continue;
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory()) {
                zip.putNextEntry(new ZipEntry(rel + "/"));
                zip.closeEntry();
                zipTree(f, zip);
            } else {
                try (InputStream in = new FileInputStream(f)) {
                    zip.putNextEntry(new ZipEntry(rel));
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                    zip.closeEntry();
                } catch (IOException locked) {
                    // a file the server holds locked (session.lock on Windows) - skip it, the rest is fine
                }
            }
        }
    }

    private Map<String, Object> backupDelete(String name) throws IOException {
        Files.delete(backupFile(name).toPath());
        return obj();
    }

    private File backupFile(String name) throws IOException {
        String n = new File(name == null ? "" : name).getName();
        if (!n.matches("[\\w.-]+\\.zip")) throw new JobError(400, "Not a backup file");
        File f = new File(backups, n);
        if (!f.isFile()) throw new JobError(404, "Not found");
        return f;
    }

    // ------------------------------------------------------------------ helpers

    /** A path inside the server folder, checked on its real location; mustExist=false allows a new name. */
    private File resolve(String rel, boolean mustExist) throws IOException {
        String clean = (rel == null ? "" : rel).replace('\\', '/');
        if (clean.indexOf('\0') >= 0) throw new JobError(400, "Bad path");
        while (clean.startsWith("/")) clean = clean.substring(1);
        File f = clean.isEmpty() ? root : new File(root, clean);
        File real;
        if (f.exists()) {
            real = f.getCanonicalFile();
        } else {
            if (mustExist) throw new JobError(404, "Not found");
            File parent = f.getParentFile() == null ? root : f.getParentFile().getCanonicalFile();
            real = new File(parent, f.getName());
        }
        if (!inside(real)) throw new JobError(403, "Path is outside the server folder");
        return real;
    }

    private boolean inside(File f) {
        String r = root.getPath(), p = f.getPath();
        return p.equals(r) || p.startsWith(r.endsWith(File.separator) ? r : r + File.separator);
    }

    private String rel(File f) {
        String r = root.getPath(), p = f.getPath();
        if (p.equals(r)) return "";
        return p.substring(r.length() + (r.endsWith(File.separator) ? 0 : 1)).replace(File.separatorChar, '/');
    }

    private static String jar(String name) {
        String n = new File(name == null ? "" : name).getName();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".jar") || n.startsWith(".")) throw new JobError(400, "Not a plugin or mod jar");
        return n;
    }

    private static void deleteTree(File f) throws IOException {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        Files.delete(f.toPath());
    }

    private void reply(String jid, boolean ok, Map<String, Object> result, String error, int status) {
        Map<String, Object> o = obj("ok", ok);
        if (result != null) o.put("result", result);
        if (error != null) o.put("error", error);
        o.put("status", status);
        try {
            HttpURLConnection c = open(jobsUrl + "/" + jid, "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = Json.write(o).getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            RemoteLink.read(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream());
        } catch (IOException ignored) {
            // the panel restarted in between - it times the job out by itself
        }
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && pinned != null) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            https.setSSLSocketFactory(pinned.getSocketFactory());
            https.setHostnameVerifier((h, s) -> true);                 // the pinned fingerprint is the identity check
        }
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("X-Link-Secret", secret);
        c.setRequestProperty("User-Agent", core.userAgent());
        return c;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** An error with the HTTP status the panel should pass on to your browser. */
    static final class JobError extends RuntimeException {
        final int status;

        JobError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
