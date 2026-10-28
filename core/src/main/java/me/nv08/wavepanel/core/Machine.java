package me.nv08.wavepanel.core;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * This JVM and this machine: the numbers every platform reports the same way. Process CPU comes from the CPU time used
 * since the last sample (% of ONE core, like the panel's own servers); machine CPU on Linux from /proc/stat deltas
 * (what top/htop show), elsewhere the JVM's own figure, else the load average.
 */
final class Machine {
    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();
    private static final Method PROC_CPU = osMethod("getProcessCpuLoad");
    private static final Method PROC_TIME = osMethod("getProcessCpuTime");
    private static final Method SYS_CPU = osMethod("getCpuLoad", "getSystemCpuLoad");
    private static final Method RAM_TOTAL = osMethod("getTotalMemorySize", "getTotalPhysicalMemorySize");
    private static final Method RAM_FREE = osMethod("getFreeMemorySize", "getFreePhysicalMemorySize");

    private long lastCpuTime = -1, lastCpuWall;
    private long[] statTotal, statIdle;
    private final String cpuModel = readCpuModel();
    private final long memLimit = readMemLimit();

    private static Method osMethod(String... names) {
        try {
            Class<?> c = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!c.isInstance(OS)) return null;
            for (String n : names) {
                try {
                    return c.getMethod(n);
                } catch (NoSuchMethodException ignored) {
                    // older/newer JDK name - try the next
                }
            }
        } catch (Throwable ignored) {
            // not a HotSpot-style JVM
        }
        return null;
    }

    /** "cpu", "mem", "threads", "jvmUptime", "heapUsed", "heapMax" and "machine":{...} - appended to a JSON object. */
    synchronized void append(StringBuilder j) {
        Runtime rt = Runtime.getRuntime();
        j.append(",\"heapUsed\":").append(rt.totalMemory() - rt.freeMemory());
        j.append(",\"heapMax\":").append(rt.maxMemory());
        Double cpuNs = invokeDouble(PROC_TIME);
        long wall = System.nanoTime();
        if (cpuNs != null && cpuNs >= 0) {
            if (lastCpuTime >= 0 && wall > lastCpuWall) j.append(",\"cpu\":").append(Json.num(Math.max(0, (cpuNs - lastCpuTime) / (wall - lastCpuWall) * 100)));
            lastCpuTime = cpuNs.longValue();
            lastCpuWall = wall;
        } else {
            Double pcpu = invokeDouble(PROC_CPU);
            if (pcpu != null && pcpu >= 0) j.append(",\"cpu\":").append(Json.num(pcpu * rt.availableProcessors() * 100));
        }
        j.append(",\"mem\":").append(rt.totalMemory());
        if (memLimit > 0) j.append(",\"memLimit\":").append(memLimit);
        j.append(",\"threads\":").append(ManagementFactory.getThreadMXBean().getThreadCount());
        j.append(",\"jvmUptime\":").append(ManagementFactory.getRuntimeMXBean().getUptime());

        j.append(",\"machine\":{");
        double[] stat = procStat();
        Double scpu = invokeDouble(SYS_CPU);
        double load = OS.getSystemLoadAverage();
        int cores = rt.availableProcessors();
        double cpuNow = stat != null ? stat[0] : scpu != null && scpu >= 0 ? scpu * 100 : load >= 0 ? Math.min(100, load / cores * 100) : -1;
        j.append("\"cpu\":").append(Json.num(cpuNow));
        if (stat != null && stat.length > 1) {
            j.append(",\"coresLoad\":[");
            for (int i = 1; i < stat.length; i++) j.append(i > 1 ? "," : "").append(Json.num(stat[i]));
            j.append(']');
        }
        if (cpuModel != null) j.append(",\"cpuModel\":").append(Json.str(cpuModel));
        Double ramT = invokeDouble(RAM_TOTAL), ramF = invokeDouble(RAM_FREE);
        if (ramT != null && ramF != null) j.append(",\"ramTotal\":").append(ramT.longValue()).append(",\"ramUsed\":").append((long) (ramT - ramF));
        File here = new File(".");
        j.append(",\"diskTotal\":").append(here.getTotalSpace()).append(",\"diskUsed\":").append(here.getTotalSpace() - here.getUsableSpace());
        j.append(",\"cores\":").append(stat != null && stat.length > 1 ? stat.length - 1 : cores);
        j.append(",\"load\":").append(Json.num(Math.max(0, load)));
        j.append(",\"os\":").append(Json.str(System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        j.append('}');
    }

    /** Busy % since the last call, [0] = whole machine, [1..] = each core; null where /proc/stat doesn't exist. */
    private double[] procStat() {
        File f = new File("/proc/stat");
        if (!f.canRead()) return null;
        List<long[]> rows = new ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                if (!line.startsWith("cpu")) {
                    if (!rows.isEmpty()) break;
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                long total = 0;
                for (int i = 1; i < Math.min(p.length, 9); i++) total += Long.parseLong(p[i]);   // user..steal
                long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);     // idle + iowait
                rows.add(new long[]{total, idle});
            }
        } catch (Exception e) {
            return null;
        }
        if (rows.isEmpty()) return null;
        long[] tot = new long[rows.size()], idl = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) { tot[i] = rows.get(i)[0]; idl[i] = rows.get(i)[1]; }
        double[] out = null;
        if (statTotal != null && statTotal.length == tot.length) {
            out = new double[tot.length];
            for (int i = 0; i < tot.length; i++) {
                long dt = tot[i] - statTotal[i], di = idl[i] - statIdle[i];
                out[i] = dt > 0 ? Math.max(0, Math.min(100, (1 - (double) di / dt) * 100)) : 0;
            }
        }
        statTotal = tot;
        statIdle = idl;
        return out;
    }

    /**
     * The memory this server may use in all (its container / systemd unit: the cgroup limit), in bytes - what a Split
     * shares out. 0 when there's no limit (then the machine's RAM is it) or it's not Linux.
     */
    private static long readMemLimit() {
        List<String> files = new ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(new File("/proc/self/cgroup").toPath())) {
                if (line.startsWith("0::")) files.add("/sys/fs/cgroup" + line.substring(3).trim() + "/memory.max");      // cgroup v2
            }
        } catch (Exception ignored) {
            // not Linux
        }
        files.add("/sys/fs/cgroup/memory.max");                                    // v2, inside a container
        files.add("/sys/fs/cgroup/memory/memory.limit_in_bytes");                  // v1
        for (String f : files) {
            try {
                String v = new String(java.nio.file.Files.readAllBytes(new File(f).toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim();
                long n = "max".equals(v) ? 0 : Long.parseLong(v);
                if (n > 0 && n < (1L << 50)) return n;                             // v1 says "no limit" with a huge number
            } catch (Exception ignored) {
                // not there - the next one
            }
        }
        return 0;
    }

    private static String readCpuModel() {
        try {
            File f = new File("/proc/cpuinfo");
            if (f.canRead()) {
                for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                    if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception ignored) {
            // not Linux
        }
        String id = System.getenv("PROCESSOR_IDENTIFIER");     // Windows
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }

    private static Double invokeDouble(Method m) {
        if (m == null) return null;
        try {
            Object o = m.invoke(OS);
            return o instanceof Number ? ((Number) o).doubleValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
