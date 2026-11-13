package br.com.bloco.servidor;

/** One local Bedrock console line, never passed through a shell. */
public final class ConsoleCommand {
    private ConsoleCommand() { }
    public static String normalize(String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use somente uma linha de comando.");
        String result = value.trim();
        if (result.startsWith("/")) result = result.substring(1).trim();
        if (result.isEmpty() || result.length() > 500) throw new IllegalArgumentException("Digite um comando de até 500 caracteres.");
        return result;
    }
}
