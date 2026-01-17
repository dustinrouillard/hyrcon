package to.dstn.hytale.hyrcon;

import java.util.Objects;

@FunctionalInterface
public interface CommandExecutor {
    CommandResponse execute(String command);

    default CommandResponse executeValidated(String command) {
        return execute(Objects.requireNonNull(command, "command"));
    }
}
