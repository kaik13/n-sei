package br.com.bloco.servidor;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Brief, non-reserving bind checks made before starting the server. */
public final class PortDiagnostics {
    private PortDiagnostics() { }

    public static final class Result {
        public final boolean available;
        public final String detail;
        Result(boolean available, String detail) { this.available = available; this.detail = detail; }
    }

    public static Result test(boolean tcp, boolean ipv6, int port) {
        try {
            InetAddress address = InetAddress.getByName(ipv6 ? "::" : "0.0.0.0");
            if (tcp) {
                try (ServerSocket socket = new ServerSocket()) {
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress(address, port));
                }
            } else {
                try (DatagramSocket socket = new DatagramSocket(null)) {
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress(address, port));
                }
            }
            return new Result(true, "aberta para teste; liberada em seguida");
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = "sem detalhes do sistema";
            message = message.replace('\n', ' ').replace('\r', ' ');
            if (message.length() > 240) message = message.substring(0, 240);
            // A BindException can indicate permissions, unavailable IPv6 or
            // another restriction, not necessarily an occupied port.
            return new Result(false, error.getClass().getSimpleName() + ": " + message);
        }
    }
}
