package br.com.bloco.servidor;

/** Conservative player limit for the phone-hosted LAN server. */
public final class PlayerSettings {
    public static final int MIN_SLOTS = 1;
    public static final int MAX_SLOTS = 5;
    public static final int DEFAULT_SLOTS = MAX_SLOTS;

    private PlayerSettings() { }

    public static boolean valid(int slots) {
        return slots >= MIN_SLOTS && slots <= MAX_SLOTS;
    }

    /** Clamp migrated or externally supplied settings so the server never exceeds the product limit. */
    public static int clamp(int slots) {
        return Math.max(MIN_SLOTS, Math.min(MAX_SLOTS, slots));
    }
}
