package br.com.bloco.servidor;

/** Conservative controls for real Bedrock settings; these do not overclock the phone. */
public final class PerformanceSettings {
    public static final int DEFAULT_VIEW = 5, DEFAULT_TICK = 4, DEFAULT_THREADS = 0;
    private PerformanceSettings() { }
    public static boolean valid(int view, int tick, int threads) {
        return view >= 5 && view <= 32 && tick >= 4 && tick <= 12 && tick <= view && threads >= 0 && threads <= 32;
    }
    public static int view(int value) { return Math.max(5, Math.min(32, value)); }
    public static int tick(int value, int view) { return Math.min(view, Math.max(4, Math.min(12, value))); }
    public static int threads(int value) { return Math.max(0, Math.min(32, value)); }
}
