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
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("127.0.0.1"), port));
            byte[] response = new byte[4096]; DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return Arrays.copyOf(response, packet.getLength());
        }
    }
    public static boolean responds(int port) {
        try {
            ByteBuffer ping = ByteBuffer.allocate(1 + 8 + 16 + 8);
            ping.put((byte) 0x01).putLong(System.currentTimeMillis()).put(MAGIC).putLong(0x1234567812345678L);
            byte[] reply = exchange(ping.array(), port);
            if (reply.length >= 35 && (reply[0] & 255) == 0x1c && Arrays.equals(Arrays.copyOfRange(reply, 9, 25), MAGIC)) return true;
        } catch (Exception ignored) { }
        try {
            ByteBuffer request = ByteBuffer.allocate(1 + 16 + 1 + 40);
            request.put((byte) 0x05).put(MAGIC).put((byte) 11);
            byte[] reply = exchange(request.array(), port);
            int id = reply.length == 0 ? 0 : reply[0] & 255;
            return reply.length >= 26 && (id == 0x06 || id == 0x1a) && Arrays.equals(Arrays.copyOfRange(reply, 1, 17), MAGIC);
        } catch (Exception ignored) { return false; }
    }
}
