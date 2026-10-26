import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Downloads the three jars WavePanel compiles against into libs/ - straight from their official sites - and checks
 * each one's SHA-256. They are only needed to build: none of their code ends up in WavePanel.jar.
 *
 * Run it from this folder (JDK 11 or newer):   java tools/FetchLibs.java
 */
public class FetchLibs {
    static final String[][] LIBS = {
        {"BungeeCord.jar", "https://hub.spigotmc.org/jenkins/job/BungeeCord/2107/artifact/bootstrap/target/BungeeCord.jar",
            "9f178e6b6db2b09fbc25407f872f30a007ee0a171622787407318662377e9b48"},
        {"velocity.jar", "https://fill-data.papermc.io/v1/objects/b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/velocity-3.5.1-615.jar",
            "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3"},
        {"brigadier-1.3.10.jar", "https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
            "c8ee4136e474ac7723ca2b432ec8d1a2bc88ef7d1ec57c314ba9e33cdc83dd75"},
    };

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("libs");
        Files.createDirectories(dir);
        boolean ok = true;
        for (String[] lib : LIBS) {
            Path f = dir.resolve(lib[0]);
            if (!Files.isRegularFile(f) || !sha256(f).equals(lib[2])) {
                System.out.println("downloading " + lib[1]);
                download(lib[1], f);
            }
            String got = sha256(f);
            boolean good = got.equals(lib[2]);
            ok &= good;
            System.out.println((good ? "OK     " : "WRONG  ") + lib[0] + "  sha256 " + got);
        }
        if (!ok) {
            System.out.println("A file doesn't match its SHA-256 - don't build with it.");
            System.exit(1);
        }
    }

    /** HttpURLConnection doesn't follow a redirect to another host by itself; the SHA-256 check stays the judge. */
    static void download(String url, Path to) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "WavePanel-source-build");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                url = URI.create(url).resolve(c.getHeaderField("Location")).toString();
                continue;
            }
            if (code != 200) throw new IllegalStateException(url + ": HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        throw new IllegalStateException("too many redirects: " + url);
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format("%02x", x));
        return b.toString();
    }
}
