package me.nv08.wavepanel.agent;

import me.nv08.wavepanel.core.Json;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The server list ping (1.7+): what any Minecraft server tells a client before it joins - version, players, MOTD. */
final class Ping {
    private Ping() {}

    static Map<String, Object> status(String host, int port, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(hs);
            varInt(h, 0x00);                                   // handshake
            varInt(h, 47);                                     // protocol: any - the reply carries the server's own
            byte[] addr = host.getBytes(StandardCharsets.UTF_8);
            varInt(h, addr.length);
            h.write(addr);
            h.writeShort(port);
            varInt(h, 1);                                      // next: status
            packet(out, hs.toByteArray());
            packet(out, new byte[]{0x00});                     // status request
            out.flush();

            readVarInt(in);                                    // packet length
            if (readVarInt(in) != 0x00) throw new IOException("not a status reply");
            int len = readVarInt(in);
            if (len < 0 || len > 1 << 21) throw new IOException("bad status length " + len);
            byte[] json = new byte[len];
            in.readFully(json);
            return Json.obj(Json.parse(new String(json, StandardCharsets.UTF_8)));
        }
    }

    private static void packet(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        varInt(d, body.length);
        d.write(body);
        out.write(b.toByteArray());
    }

    private static void varInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 35) throw new IOException("varint too long");
        }
    }
}
