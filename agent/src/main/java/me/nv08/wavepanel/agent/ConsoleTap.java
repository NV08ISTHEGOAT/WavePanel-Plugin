package me.nv08.wavepanel.agent;

import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.LineSource;
import me.nv08.wavepanel.core.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * The server's console, shared: everything printed is copied into a line buffer (for servers without a log file),
 * and the server reads its input through here - so WavePanel can type commands into it, and (in the basic version)
 * answer "wavepanel ..." lines itself. Lines pass through byte for byte; when the real stdin ends (a hosting panel
 * without a console), the server's input stays open for WavePanel.
 */
final class ConsoleTap {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern DONE = Pattern.compile("Done \\(\\d+[.,]?\\d*s\\)!");
    private static final Pattern OURS = Pattern.compile("^\\s*/?wavepanel(\\s.*)?$", Pattern.CASE_INSENSITIVE);

    final LineSource.Buffer lines = new LineSource.Buffer();
    private final QueueIn in = new QueueIn();
    private final InputStream original;
    private volatile boolean done;
    private volatile Core core;
    private volatile Log log;

    private ConsoleTap(InputStream original) { this.original = original; }

    static ConsoleTap install() {
        ConsoleTap t = new ConsoleTap(System.in);
        System.setIn(t.in);
        System.setOut(t.wrap(System.out));
        System.setErr(t.wrap(System.err));
        Thread r = new Thread(t::pump, "WavePanel-console");
        r.setDaemon(true);
        r.start();
        return t;
    }

    boolean sawDone() { return done; }

    /** The basic version runs: "wavepanel ..." typed in the console is its now. */
    void handTo(Core c, Log l) {
        log = l;
        core = c;
    }

    /** The full version runs: the game's own console knows /wavepanel - pass everything on. */
    void passThrough() { core = null; }

    /** A command from WavePanel, as if typed into the console. */
    void inject(String command) {
        in.offer((command.replace("\n", " ").replace("\r", " ") + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The real stdin, a line at a time, into the server's input (minus the lines that are ours). */
    private void pump() {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = original.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    line.write(buf[i]);
                    if (buf[i] == '\n') {
                        deliver(line.toByteArray());
                        line.reset();
                    }
                }
            }
        } catch (IOException ignored) {
            // stdin closed
        }
        if (line.size() > 0) deliver(line.toByteArray());
    }

    private void deliver(byte[] raw) {
        Core c = core;
        if (c != null) {
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            if (OURS.matcher(text).matches()) {
                final Log l = log;
                c.command(Core.args(text), l::info);
                return;
            }
        }
        in.offer(raw);
    }

    private PrintStream wrap(PrintStream target) {
        Charset cs = charsetOf(target);
        try {
            return new PrintStream(new Tee(target), true, cs.name());
        } catch (UnsupportedEncodingException e) {
            return new PrintStream(new Tee(target), true);
        }
    }

    /** The console's own charset (Java 18+ PrintStream.charset()), so non-ASCII text stays as it was. */
    private static Charset charsetOf(PrintStream s) {
        try {
            return (Charset) PrintStream.class.getMethod("charset").invoke(s);
        } catch (Throwable t) {
            String e = System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding"));
            try {
                return e == null ? Charset.defaultCharset() : Charset.forName(e);
            } catch (Exception bad) {
                return Charset.defaultCharset();
            }
        }
    }

    private void printed(String line) {
        String clean = ANSI.matcher(line).replaceAll("").replace("\r", "");
        if (!done && DONE.matcher(clean).find()) done = true;
        lines.add(clean);
    }

    /** Everything written goes on to the real stream unchanged; complete lines are copied off as text. */
    private final class Tee extends OutputStream {
        private final PrintStream target;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Tee(PrintStream target) { this.target = target; }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            collect(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            target.write(b, off, len);
            for (int i = off; i < off + len; i++) collect(b[i]);
        }

        @Override
        public void flush() { target.flush(); }

        private void collect(int b) {
            if (b == '\n') {
                printed(new String(pending.toByteArray(), StandardCharsets.UTF_8));
                pending.reset();
            } else if (pending.size() < 16384) {
                pending.write(b);
            }
        }
    }

    /** The server's stdin: whatever was queued, in order; it blocks (never ends) while there's nothing. */
    private static final class QueueIn extends InputStream {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile byte[] cur;
        private volatile int pos;

        void offer(byte[] b) { if (b.length > 0) queue.add(b); }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            try {
                while (cur == null || pos >= cur.length) {
                    cur = queue.take();
                    pos = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int available() {
            byte[] c = cur;
            return c == null ? 0 : Math.max(0, c.length - pos);
        }
    }
}
