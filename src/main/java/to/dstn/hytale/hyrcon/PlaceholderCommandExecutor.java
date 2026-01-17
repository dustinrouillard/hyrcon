package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaceholderCommandExecutor implements CommandExecutor {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final CommandResponse NOT_READY = CommandResponse.failure(
        "Command dispatcher not yet wired"
    );

    private final AtomicReference<CommandExecutor> delegate =
        new AtomicReference<>();

    public void install(CommandExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        if (!delegate.compareAndSet(null, executor)) {
            throw new IllegalStateException(
                "Command executor already installed"
            );
        }
        LOGGER.atInfo().log(
            "Command executor delegate installed: %s",
            executor.getClass().getName()
        );
    }

    public void reset() {
        CommandExecutor previous = delegate.getAndSet(null);
        if (previous != null) {
            LOGGER.atInfo().log(
                "Command executor delegate reset from %s",
                previous.getClass().getName()
            );
        }
    }

    public boolean isReady() {
        return delegate.get() != null;
    }

    @Override
    public CommandResponse execute(String command) {
        CommandExecutor executor = delegate.get();
        if (executor == null) {
            return NOT_READY;
        }
        return executor.execute(command);
    }
}
