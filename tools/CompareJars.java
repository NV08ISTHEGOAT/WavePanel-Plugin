import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares what is INSIDE two jars, file by file: every class file, plugin.yml, the manifest... A jar is a zip, and a
 * zip stores the time each file was packed - so two builds of the same source get different checksums for the jar
 * file itself, while every file inside is identical.
 *
 * Usage (JDK 11 or newer):   java tools/CompareJars.java dist/target/WavePanel.jar WavePanel.jar
 */
public class CompareJars {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("usage: java tools/CompareJars.java <your build> <the official WavePanel.jar>");
            System.exit(2);
        }
        Map<String, String> a = contents(args[0]), b = contents(args[1]);
        Set<String> all = new TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        int same = 0, different = 0;
        for (String name : all) {
            String x = a.get(name), y = b.get(name);
            if (x != null && x.equals(y)) {
                same++;
                continue;
            }
            different++;
            System.out.println((x == null ? "only in " + args[1] : y == null ? "only in " + args[0] : "DIFFERENT") + ":  " + name);
        }
        System.out.println(same + " files identical, " + different + " different");
        System.exit(different == 0 ? 0 : 1);
    }

    /** file name inside the jar -> SHA-256 of its bytes (folders left out) */
    static Map<String, String> contents(String jar) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = z.getInputStream(entry)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
                StringBuilder hex = new StringBuilder();
                for (byte x : md.digest()) hex.append(String.format("%02x", x));
                out.put(entry.getName(), hex.toString());
            }
        }
        return out;
    }
}
