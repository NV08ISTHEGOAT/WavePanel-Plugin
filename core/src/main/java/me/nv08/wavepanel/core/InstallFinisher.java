package me.nv08.wavepanel.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The last step of a version install where the server's own jar can't be replaced while its JVM runs (Windows locks
 * open files): started by Installer.apply just before that JVM exits, it waits until the jar is free, swaps it, and
 * tidies up. {@code java -cp WavePanel.jar me.nv08.wavepanel.core.InstallFinisher <new jar> <server jar> <stage dir>}
 */
public final class InstallFinisher {
    /**
     * Its name as plain text: the shutdown hooks that start it run after Paper has closed the plugin's jar, so they
     * can't load a class from it any more - not even to ask for its name.
     */
    static final String NAME = "me.nv08.wavepanel.core.InstallFinisher";

    private InstallFinisher() {}

    public static void main(String[] a) throws Exception {
        File newJar = new File(a[0]), serverJar = new File(a[1]), stage = new File(a[2]);
        for (int i = 0; i < 1200; i++) {                         // every 100 ms, up to 2 minutes
            try {
                Files.move(newJar.toPath(), serverJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                delete(stage);
                System.out.println(new java.util.Date() + " helper: swapped " + serverJar.getName() + " after " + (i * 100) + " ms");
                return;
            } catch (IOException stillLocked) {
                Thread.sleep(100);
            }
        }
        System.out.println(new java.util.Date() + " helper: " + serverJar.getName() + " stayed locked for 2 minutes - the new jar is in "
                + newJar + ", swap it by hand");
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
