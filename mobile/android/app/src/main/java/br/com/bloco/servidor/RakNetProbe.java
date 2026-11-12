package br.com.bloco.servidor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Minimal local RakNet health probe, not a simulation of an actual player join. */
public final class RakNetProbe {
    private static final byte[] MAGIC = new byte[]{0, (byte) 0xff, (byte) 0xff, 0, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};
    private RakNetProbe() { }
    private static byte[] exchange(byte[] payload, int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1000);
            socket.connect(InetAddress.getByName("127.0.0.1"), port);
            socket.send(new DatagramPacket(payload, payload.length));
            byte[] response = new byte[4096]; DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return Arrays.copyOf(response, packet.getLength());
        }
    }
    static boolean validPong(byte[] reply, long timestamp) {
        // RakNet pong: id, echoed timestamp, server GUID, magic, string length, server ID.
        if (reply.length < 35 || (reply[0] & 255) != 0x1c ||
                ByteBuffer.wrap(reply, 1, 8).getLong() != timestamp ||
                !Arrays.equals(Arrays.copyOfRange(reply, 17, 33), MAGIC)) return false;
        int serverIdLength = ((reply[33] & 255) << 8) | (reply[34] & 255);
        return serverIdLength <= reply.length - 35;
    }
    static boolean validConnectionReply(byte[] reply) {
        if (reply.length == 0) return false;
        int id = reply[0] & 255;
        // Open Connection Reply 1, or Incompatible Protocol Version.
        int magicOffset = id == 0x06 ? 1 : id == 0x19 ? 2 : -1;
        int minimum = id == 0x06 ? 28 : id == 0x19 ? 26 : Integer.MAX_VALUE;
        return reply.length >= minimum &&
                Arrays.equals(Arrays.copyOfRange(reply, magicOffset, magicOffset + 16), MAGIC);
    }
    public static boolean responds(int port) {
        try {
            long timestamp = System.currentTimeMillis();
            ByteBuffer ping = ByteBuffer.allocate(1 + 8 + 16 + 8);
            ping.put((byte) 0x01).putLong(timestamp).put(MAGIC).putLong(0x1234567812345678L);
            if (validPong(exchange(ping.array(), port), timestamp)) return true;
        } catch (Exception ignored) { }
        try {
            ByteBuffer request = ByteBuffer.allocate(1 + 16 + 1 + 40);
            request.put((byte) 0x05).put(MAGIC).put((byte) 11);
            return validConnectionReply(exchange(request.array(), port));
        } catch (Exception ignored) { return false; }
    }
}
