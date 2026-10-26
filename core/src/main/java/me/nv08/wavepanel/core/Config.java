package me.nv08.wavepanel.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config.yml, the same file on every platform (and the one PanelLink / WavePanel 3.0 already wrote): read with a
 * small reader for exactly this shape (top-level keys plus the "remote:" section), written back from a commented
 * template so the explanations stay in the file. No YAML library: most platforms don't hand one to plugins.
 */
public final class Config {
    private static final String[][] DEFAULTS = {
        {"panel", "https://wavepanel.xyz"}, {"refresh-ticks", "40"},
        {"remote.enabled", "false"}, {"remote.url", ""}, {"remote.secret", ""}, {"remote.name", ""},
        {"remote.cert-sha256", ""}, {"remote.interval-ticks", "40"}, {"remote.allow-files", "true"},
        {"remote.restart-exit-code", "1"}, {"remote.backups-keep", "3"},
        {"local", "false"}, {"port", "25590"},
    };

    private final File file;
    private final Map<String, String> v = new LinkedHashMap<>();

    public Config(File file) {
        this.file = file;
        for (String[] d : DEFAULTS) v.put(d[0], d[1]);
        if (file.isFile()) {
            try {
                v.putAll(read(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
                // unreadable: the defaults stand, and the next save writes a clean file
            }
        }
    }

    public boolean exists() { return file.isFile(); }

    public String get(String k) {
        String s = v.get(k);
        return s == null ? "" : s;
    }

    public boolean bool(String k) { return "true".equalsIgnoreCase(get(k).trim()); }

    public int integer(String k, int dflt) {
        try {
            return Integer.parseInt(get(k).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public void set(String k, Object value) { v.put(k, value == null ? "" : String.valueOf(value)); }

    /** The keys a config.yml from elsewhere (PanelLink's) carries. */
    public static Map<String, String> read(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        String section = null;
        for (String raw : lines) {
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) continue;
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            if (indent == 0) {
                if (rest.isEmpty() || rest.startsWith("#")) { section = key; continue; }
                section = null;
                out.put(key, value(rest));
            } else if (section != null) {
                out.put(section + "." + key, value(rest));
            }
        }
        return out;
    }

    /** A scalar: 'single' or "double" quoted, or plain with an optional trailing " # comment". */
    private static String value(String s) {
        if (s.startsWith("'")) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') { b.append('\''); i++; continue; }
                    break;
                }
                b.append(c);
            }
            return b.toString();
        }
        if (s.startsWith("\"")) {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') break;
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                    continue;
                }
                b.append(c);
            }
            return b.toString();
        }
        int hash = s.indexOf(" #");
        return (hash >= 0 ? s.substring(0, hash) : s).trim();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public void save() throws IOException {
        String t = TEMPLATE;
        for (Map.Entry<String, String> e : v.entrySet()) {
            String val = e.getValue();
            boolean plain = val.matches("true|false|-?\\d+");
            t = t.replace("${" + e.getKey() + "}", plain ? val : quote(val));
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir);
        File tmp = new File(dir, file.getName() + ".tmp");
        Files.write(tmp.toPath(), t.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static final String TEMPLATE = String.join("\n",
        "# WavePanel.jar - connects this server to WavePanel (https://wavepanel.xyz).",
        "#",
        "# 1. Make a free account on wavepanel.xyz and click \"Connect a server\".",
        "# 2. Run the command it gives you in this server's console (or in-game as an op):",
        "#        wavepanel connect WP-XXXX-XXXX",
        "#    That fills in the \"remote\" part below by itself. Nothing to open on your firewall.",
        "",
        "# Where connect codes are checked (only change it if you run your own WavePanel).",
        "panel: ${panel}",
        "",
        "# How often the stats are refreshed, in ticks (40 = every 2 seconds).",
        "refresh-ticks: ${refresh-ticks}",
        "",
        "# -- The connection (filled in by \"wavepanel connect\") ------------------------",
        "# This server PUSHES stats + new console lines to WavePanel over HTTPS and runs the console",
        "# commands WavePanel sends back. Nothing listens here, no port to open.",
        "remote:",
        "  enabled: ${remote.enabled}",
        "  url: ${remote.url}",
        "  secret: ${remote.secret}",
        "  name: ${remote.name}",
        "  # Only for a panel with a self-signed certificate: its SHA-256 fingerprint. Empty = the normal check.",
        "  cert-sha256: ${remote.cert-sha256}",
        "  interval-ticks: ${remote.interval-ticks}",
        "  # WavePanel's file manager, plugins/mods page and backups for this server (its own folder only).",
        "  # Whoever you give access to this server in WavePanel can then change its files. false = stats, console and players only.",
        "  allow-files: ${remote.allow-files}",
        "  # Restart in WavePanel on a hosted server: the server saves, stops and exits with this code. Non-zero = \"crashed\",",
        "  # so the host's auto-restart / crash detection starts it again. 0 = a plain stop (start it yourself).",
        "  restart-exit-code: ${remote.restart-exit-code}",
        "  backups-keep: ${remote.backups-keep}            # backups are zips in this folder's backups/; older ones are removed",
        "",
        "# -- Only if you run WavePanel yourself on this same machine -----------------",
        "# Serves the stats on 127.0.0.1 ONLY (read-only, unreachable from outside). \"wavepanel connect\" turns it off.",
        "local: ${local}",
        "port: ${port}",
        "");
}
