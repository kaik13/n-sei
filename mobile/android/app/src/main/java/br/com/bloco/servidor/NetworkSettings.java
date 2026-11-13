package br.com.bloco.servidor;

/** Network defaults shared by the UI, the generated properties and health checks. */
public final class NetworkSettings {
    public static final int DEFAULT_PORT = 19132;
    public static final int LAN_DISCOVERY_PORT = 7551;
    private NetworkSettings() { }

    public static boolean validPort(int port) {
        // 7551 is used independently for NetherNet LAN discovery.
        return port >= 1024 && port <= 65535 && port != LAN_DISCOVERY_PORT;
    }
    public static int portOrDefault(int port) { return validPort(port) ? port : DEFAULT_PORT; }
    public static int legacyIpv6Port(int port) { return port == 65535 ? 65534 : port + 1; }

    private static boolean atLeast(String version, int major, int minor, int patch) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length < 3) return false;
            int[] actual = { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
            int[] minimum = { major, minor, patch };
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != minimum[i]) return actual[i] > minimum[i];
            }
            return true;
        } catch (RuntimeException invalidVersion) { return false; }
    }

    // Older offline installations may still be the only installed engine.
    // NetherNet became the official BDS default in 1.26.50.
    public static boolean netherNet(String bedrockVersion) { return atLeast(bedrockVersion, 1, 26, 50); }

    public static String udpPorts(String bedrockVersion, int port, int slots) {
        // Since 1.26.60, BDS documents a single UDP port shared by all clients.
        // Earlier NetherNet versions used a port per connection, so allocate
        // a range at least as large as the configured slot count instead.
        if (!netherNet(bedrockVersion) || atLeast(bedrockVersion, 1, 26, 60)) return Integer.toString(port);
        int count = Math.max(10, Math.min(30, slots));
        int first = Math.min(port, 65535 - count + 1);
        if (first <= LAN_DISCOVERY_PORT && first + count - 1 >= LAN_DISCOVERY_PORT) first = LAN_DISCOVERY_PORT + 1;
        return first + "-" + (first + count - 1);
    }
}
