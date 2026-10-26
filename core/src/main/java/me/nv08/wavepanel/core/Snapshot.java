package me.nv08.wavepanel.core;

import java.util.ArrayList;
import java.util.List;

/** A server's own numbers, filled in by its {@link Platform} on the main thread. Unknown = left as it is. */
public final class Snapshot {
    /** The server software's own version line, e.g. "git-Paper-232 (MC: 1.21.4)" or "26.3". */
    public String software;
    /** TPS over 1, 5 and 15 minutes, or null when the platform can't tell (a proxy, the agent). */
    public double[] tps;
    public Double mspt;
    public int online;
    public int max;
    public final List<Player> players = new ArrayList<>();
    /** Worlds - or, on a proxy, the backend servers. */
    public final List<World> worlds = new ArrayList<>();

    public static final class Player {
        public String name = "?", uuid = "", world = "?", gamemode = "", server;
        public int x, y, z, ping, food = -1;
        public double health = -1, maxHealth = -1;
        public boolean op;
    }

    public static final class World {
        public String name = "?", env = "normal";
        public int players, entities = -1, chunks = -1;
    }
}
