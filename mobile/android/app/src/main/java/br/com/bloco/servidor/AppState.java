package br.com.bloco.servidor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** In-process state shared by the activity, foreground service and file operations. */
final class AppState {
    private static final Object LOCK = new Object();
    private static boolean hosting, fileOperation, ready;
    private static String status = "Desligado", detail = "Seu celular será o servidor.", lastError = "";
    private static int players = -1, slots = 10;
    private static final ArrayDeque<String> logs = new ArrayDeque<>();

    static boolean beginHosting() {
        synchronized (LOCK) {
            if (hosting || fileOperation) return false;
            hosting = true; ready = false; players = -1; lastError = ""; logs.clear();
            status = "Preparando"; detail = "Preparando o servidor neste celular…"; return true;
        }
    }
    static boolean beginFileOperation() {
        synchronized (LOCK) { if (hosting || fileOperation) return false; fileOperation = true; return true; }
    }
    static void finishFileOperation() { synchronized (LOCK) { fileOperation = false; } }
    static void message(String s) { synchronized (LOCK) { detail = s; } }
    static void running(int limit) { synchronized (LOCK) { ready = true; slots = limit; status = "Online"; detail = "Servidor rodando neste celular."; } }
    static void stopping() { synchronized (LOCK) { status = "Parando"; detail = "Salvando o mundo antes de desligar…"; } }
    static void finish(String error) {
        synchronized (LOCK) {
            hosting = false; ready = false; players = -1;
            if (error != null && !error.isEmpty()) { status = "Precisa de atenção"; detail = error; lastError = error; }
            else { status = "Desligado"; detail = "O servidor foi desligado neste celular."; }
        }
    }
    static void line(String text) {
        synchronized (LOCK) {
            if (text.length() > 2000) text = text.substring(0, 2000);
            logs.addLast(text); while (logs.size() > 250) logs.removeFirst();
        }
    }
    static void playerCount(int count, int max) { synchronized (LOCK) { players = count; slots = max; } }
    static Snapshot snapshot() {
        synchronized (LOCK) { return new Snapshot(hosting, fileOperation, ready, status, detail, players, slots, new ArrayList<>(logs)); }
    }
    static final class Snapshot {
        final boolean hosting, fileOperation, ready;
        final String status, detail;
        final int players, slots;
        final List<String> logs;
        Snapshot(boolean hosting, boolean fileOperation, boolean ready, String status, String detail, int players, int slots, List<String> logs) {
            this.hosting = hosting; this.fileOperation = fileOperation; this.ready = ready; this.status = status; this.detail = detail;
            this.players = players; this.slots = slots; this.logs = logs;
        }
    }
}
