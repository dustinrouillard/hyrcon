package to.dstn.hytale.hyrcon;

import java.util.List;
import java.util.Objects;

public final class CommandResponse {

    private final boolean success;
    private final List<String> lines;
    private final String errorMessage;

    private CommandResponse(
        boolean success,
        List<String> lines,
        String errorMessage
    ) {
        this.success = success;
        this.lines = lines;
        this.errorMessage = errorMessage;
    }

    public static CommandResponse success(List<String> lines) {
        return new CommandResponse(true, sanitizeLines(lines), null);
    }

    public static CommandResponse success(String line) {
        return success(List.of(Objects.requireNonNull(line, "line")));
    }

    public static CommandResponse failure(String errorMessage) {
        return failure(errorMessage, List.of());
    }

    public static CommandResponse failure(
        String errorMessage,
        List<String> lines
    ) {
        Objects.requireNonNull(errorMessage, "errorMessage");
        return new CommandResponse(false, sanitizeLines(lines), errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public List<String> lines() {
        return lines;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean hasErrorMessage() {
        return errorMessage != null && !errorMessage.isEmpty();
    }

    private static List<String> sanitizeLines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            return List.of();
        }
        return List.copyOf(lines);
    }

    @Override
    public String toString() {
        return (
            "CommandResponse{" +
            "success=" +
            success +
            ", lines=" +
            lines +
            ", errorMessage='" +
            errorMessage +
            '\'' +
            '}'
        );
    }
}
