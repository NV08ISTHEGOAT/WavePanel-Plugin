package me.nv08.wavepanel.core;

import java.io.File;

/**
 * What one kind of server gives the core: Paper/Folia (plugins), BungeeCord/Waterfall, Velocity, Sponge, Fabric/Quilt,
 * Forge, NeoForge, or the Java agent for anything else (vanilla, NanoLimbo...). The core does everything else.
 */
public interface Platform {
    /** Short id: paper, folia, bungee, velocity, sponge, fabric, forge, neoforge, agent. */
    String id();

    /** What the panel shows, e.g. "Paper 1.21.4" or "Fabric 26.3". */
    String displayName();

    /** Where config.yml and backups/ live. */
    File dataDir();

    /** The server's own console log (logs/latest.log, proxy.log.0...), or null when the platform hands lines itself. */
    File logFile();

    /** Console lines the platform captures itself (the agent), or null to follow {@link #logFile()}. */
    default LineSource lines() { return null; }

    /** "plugins", "mods", or null when this kind of server has no add-on folder. */
    String addonsFolder();

    /** A proxy (Velocity, BungeeCord): no worlds to save, players are spread over backend servers. */
    boolean isProxy();

    Log log();

    /** Runs r on the server's main (or global) thread - or right away when the platform's API is thread-safe. */
    void runSync(Runnable r);

    /** Runs a console command. Called on the main thread (via {@link #runSync}). */
    void dispatch(String command);

    /** Fills in this server's own numbers. Called on the main thread. */
    void collect(Snapshot s);

    /**
     * Restart pressed in WavePanel: save and stop. {@code exitCode} != 0 makes a hosting panel see a "crash" and start
     * it again (the core already registered the exit); a platform with a real restart may use that instead.
     */
    void restart(int exitCode);
}
