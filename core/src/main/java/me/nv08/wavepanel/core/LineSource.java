package me.nv08.wavepanel.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Where the console lines come from: the server's log file - or lines a platform captured itself (the agent). */
public interface LineSource {
    /** The lines written since the last call (the first call: the recent history). */
    List<String> next();

    /** After an outage: carry on from now instead of resending what couldn't be delivered. */
    void skipToEnd();

    /** logs/latest.log and the like: follows the file across restarts (rotation / truncation). */
    final class FileTail implements LineSource {
        private final File log;
        private long pos = -1;
        private Object key;

        public FileTail(File log) { this.log = log; }

        public void skipToEnd() { pos = -2; }

        public List<String> next() {
            List<String> out = new ArrayList<>();
            if (log == null) return out;
            try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
                Object k = java.nio.file.Files.readAttributes(log.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
                long size = f.length();
                if (pos == -2) pos = size;
                if (pos < 0 || size < pos || (k != null && !k.equals(key))) {
                    pos = Math.max(0, size - 64 * 1024);              // first read / a restart: recent history
                    if (key != null) out.add("— log rotated (server restarted) —");
                }
                key = k;
                long len = Math.min(size - pos, 512 * 1024);
                if (len <= 0) return out;
                byte[] buf = new byte[(int) len];
                f.seek(pos);
                f.readFully(buf);
                String text = new String(buf, StandardCharsets.UTF_8);
                int last = text.lastIndexOf('\n');
                if (last < 0) return out;                             // no complete line yet
                pos += text.substring(0, last + 1).getBytes(StandardCharsets.UTF_8).length;
                for (String l : text.substring(0, last).split("\n", -1)) out.add(l.replace("\r", ""));
            } catch (IOException ignored) {
                // no log file (yet) - stats still go out
            }
            return out.size() > 500 ? new ArrayList<>(out.subList(out.size() - 500, out.size())) : out;
        }
    }

    /** Lines handed over as they're printed (the agent's console capture). Keeps the last 2000 until read. */
    final class Buffer implements LineSource {
        private final List<String> pending = new ArrayList<>();
        private boolean first = true;

        public synchronized void add(String line) {
            pending.add(line);
            if (pending.size() > 2000) pending.subList(0, pending.size() - 2000).clear();
        }

        public synchronized void skipToEnd() { pending.clear(); }

        public synchronized List<String> next() {
            List<String> out = new ArrayList<>(first ? pending.subList(Math.max(0, pending.size() - 300), pending.size()) : pending);
            first = false;
            pending.clear();
            return out.size() > 500 ? new ArrayList<>(out.subList(out.size() - 500, out.size())) : out;
        }
    }
}
