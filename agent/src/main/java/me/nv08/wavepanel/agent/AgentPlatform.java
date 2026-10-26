package me.nv08.wavepanel.agent;

import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Json;
import me.nv08.wavepanel.core.LineSource;
import me.nv08.wavepanel.core.Log;
import me.nv08.wavepanel.core.Platform;
import me.nv08.wavepanel.core.Snapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * The basic version, for a server WavePanel can't read from the inside (obfuscated vanilla before 26.x, SpongeVanilla,
 * limbo servers...): players and version from the server list ping, the console from logs/latest.log (or what it
 * prints), commands typed into its console - or sent over RCON when the console can't be shared.
 */
final class AgentPlatform implements Platform {
    private final Agent.Kind kind;
    private final ConsoleTap tap;
    private final Log log = Log.log4j(gameLoader());
    private volatile String version = "";
    private boolean warnedCommands;

    AgentPlatform(Agent.Kind kind, ConsoleTap tap) {
        this.kind = kind;
        this.tap = tap;
        try {
            version = Json.s(Json.obj(ping().get("version")), "name");
        } catch (IOException ignored) {
            // filled in by the first refresh
        }
    }

    /** The game's own class loader (the server thread's): where vanilla's bundler keeps Log4j. */
    private static ClassLoader gameLoader() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName()) && t.getContextClassLoader() != null) return t.getContextClassLoader();
        }
        return AgentPlatform.class.getClassLoader();
    }

    /** Does the server answer a server list ping yet? */
    static boolean answers() {
        try {
            ping();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, Object> ping() throws IOException {
        Properties p = props();
        String host = p.getProperty("server-ip", "").trim();
        return Ping.status(host.isEmpty() ? "127.0.0.1" : host, port(p), 2000);
    }

    private static int port(Properties p) {
        String opt = Agent.option("port");
        try {
            return Integer.parseInt(opt != null ? opt : p.getProperty("server-port", "25565").trim());
        } catch (NumberFormatException e) {
            return 25565;
        }
    }

    private static Properties props() {
        Properties p = new Properties();
        File f = new File("server.properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
                // defaults
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ Platform

    @Override
    public String id() {
        return kind == Agent.Kind.VANILLA ? "vanilla" : kind == Agent.Kind.SPONGE ? "sponge" : "agent";
    }

    @Override
    public String displayName() {
        String v = version;
        if (kind == Agent.Kind.VANILLA) return "Vanilla" + (v.isEmpty() ? "" : " " + v);
        if (kind == Agent.Kind.SPONGE) return "SpongeVanilla" + (v.isEmpty() ? "" : " " + v);
        return v.isEmpty() ? "Minecraft server" : v;
    }

    @Override public File dataDir() { return new File("wavepanel"); }
    @Override public File logFile() { return new File("logs", "latest.log"); }

    /** What the server printed, when it keeps no logs/latest.log. */
    @Override
    public LineSource lines() {
        return tap != null && !logFile().isFile() ? tap.lines : null;
    }

    @Override
    public String addonsFolder() {
        if (new File("mods").isDirectory()) return "mods";
        if (new File("plugins").isDirectory()) return "plugins";
        return null;
    }

    @Override public boolean isProxy() { return false; }
    @Override public Log log() { return log; }

    /** No main thread to borrow: everything here is a socket or the console. */
    @Override public void runSync(Runnable r) { r.run(); }

    @Override
    public void dispatch(String command) {
        String cmd = command.trim().replaceFirst("^/", "");
        if (tap != null) {
            tap.inject(cmd);
            return;
        }
        Properties p = props();
        String pw = p.getProperty("rcon.password", "");
        if ("true".equalsIgnoreCase(p.getProperty("enable-rcon", "false").trim()) && !pw.isEmpty()) {
            int port;
            try {
                port = Integer.parseInt(p.getProperty("rcon.port", "25575").trim());
            } catch (NumberFormatException e) {
                port = 25575;
            }
            try {
                Rcon.run(port, pw, cmd);
            } catch (IOException e) {
                log.warn("Could not run that over RCON: " + e.getMessage());
            }
            return;
        }
        if (!warnedCommands) {
            warnedCommands = true;
            log.warn("Commands from WavePanel can't reach this server's console. Turn on RCON in server.properties "
                    + "(enable-rcon=true and an rcon.password) and restart - WavePanel then sends them that way.");
        }
    }

    @Override
    public void collect(Snapshot s) {
        Map<String, Object> st;
        try {
            st = ping();
        } catch (IOException e) {
            return;                                           // starting / stopping: no numbers this time
        }
        String v = Json.s(Json.obj(st.get("version")), "name");
        if (!v.isEmpty()) version = v;
        s.software = displayName();
        Map<String, Object> players = Json.obj(st.get("players"));
        s.online = num(players.get("online"));
        s.max = num(players.get("max"));
        for (Object o : Json.arr(players.get("sample"))) {
            Map<String, Object> m = Json.obj(o);
            Snapshot.Player x = new Snapshot.Player();
            x.name = Json.s(m, "name");
            x.uuid = Json.s(m, "id");
            if (x.name.isEmpty() || x.uuid.equals("00000000-0000-0000-0000-000000000000")) continue;  // hidden from server lists
            s.players.add(x);
        }
        Snapshot.World w = new Snapshot.World();
        w.name = props().getProperty("level-name", "world").trim();
        w.players = s.online;
        s.worlds.add(w);
    }

    @Override
    public void restart(int exitCode) {
        Core.haltOnExit(exitCode);
        dispatch("stop");
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
