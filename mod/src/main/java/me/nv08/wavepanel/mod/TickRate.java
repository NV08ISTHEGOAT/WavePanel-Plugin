package me.nv08.wavepanel.mod;

/**
 * TPS for servers that don't count it themselves (Fabric, Forge, NeoForge, vanilla): ticks done per second over the
 * last 1, 5 and 15 minutes, from the server's tick counter sampled every refresh. Shorter history: what there is.
 */
public final class TickRate {
    private static final int SIZE = 1024;                       // 15 min at one sample per second, with room
    private final long[] times = new long[SIZE];
    private final int[] ticks = new int[SIZE];
    private int count, head;

    public synchronized void sample(long now, int tick) {
        if (count > 0) {
            int prev = (head - 1 + SIZE) % SIZE;
            if (tick < ticks[prev]) count = 0;                  // a new server in this JVM (single player) - start over
            else if (now - times[prev] < 900) return;           // keep ~1 sample a second at most
        }
        times[head] = now;
        ticks[head] = tick;
        head = (head + 1) % SIZE;
        if (count < SIZE) count++;
    }

    /** {1m, 5m, 15m}, or null until there are two samples. */
    public synchronized double[] tps() {
        if (count < 2) return null;
        int newest = (head - 1 + SIZE) % SIZE;
        return new double[]{over(newest, 60_000), over(newest, 300_000), over(newest, 900_000)};
    }

    private double over(int newest, long window) {
        long now = times[newest];
        int oldest = newest;
        for (int i = 1; i < count; i++) {
            int at = (newest - i + SIZE) % SIZE;
            if (now - times[at] > window) break;
            oldest = at;
        }
        long ms = now - times[oldest];
        if (ms < 1000) return 20.0;
        return Math.min(20.0, (ticks[newest] - ticks[oldest]) * 1000.0 / ms);
    }
}
