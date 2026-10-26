package me.nv08.wavepanel.agent;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * RCON to this same server (127.0.0.1), with the password from its own server.properties: how commands from WavePanel
 * reach a server whose console can't be shared (a real terminal owned by JLine).
 */
final class Rcon {
    private Rcon() {}

    static void run(int port, String password, String command) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, 1, 3, password);                                  // login
            if (read(in) != 1) throw new IOException("RCON refused the password in server.properties");
            send(out, 2, 2, command);
            read(in);
        }
    }

    private static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer p = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(p.array());
        out.flush();
    }

    /** The reply's request id (-1: login refused). */
    private static int read(DataInputStream in) throws IOException {
        byte[] head = new byte[4];
        in.readFully(head);
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 20) throw new IOException("bad RCON reply");
        byte[] rest = new byte[len];
        in.readFully(rest);
        return ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
