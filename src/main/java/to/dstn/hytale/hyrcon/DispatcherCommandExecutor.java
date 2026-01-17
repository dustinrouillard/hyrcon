package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.util.MessageUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class DispatcherCommandExecutor implements CommandExecutor {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\u001B\\[[;\\d]*[ -/]*[@-~]"
    );

    private final Duration timeout;

    public DispatcherCommandExecutor() {
        this(Duration.ofSeconds(5));
    }

    public DispatcherCommandExecutor(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public CommandResponse execute(String command) {
        Objects.requireNonNull(command, "command");
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return CommandResponse.failure("No command provided");
        }

        CollectingCommandSender sender = new CollectingCommandSender(
            ConsoleSender.INSTANCE
        );
        CompletableFuture<Void> future;
        try {
            future = CommandManager.get().handleCommand(sender, trimmed);
        } catch (RuntimeException ex) {
            LOGGER.atInfo().log(
                "Command dispatch failed before execution: %s",
                ex.toString()
            );
            return CommandResponse.failure(
                "Dispatch failed: " + ex.getMessage()
            );
        }

        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<String> output = sender.snapshot();
            if (output.isEmpty()) {
                return CommandResponse.success("Command executed: " + trimmed);
            }
            return CommandResponse.success(output);
        } catch (TimeoutException ex) {
            future.cancel(true);
            List<String> output = sender.snapshot();
            String message = String.format(
                "Command timed out after %d ms",
                timeout.toMillis()
            );
            return CommandResponse.failure(message, output);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            LOGGER.atInfo().log(
                "Command execution threw an exception: %s",
                cause.toString()
            );
            List<String> output = sender.snapshot();
            return CommandResponse.failure(
                "Execution failed: " + cause.getMessage(),
                output
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            List<String> output = sender.snapshot();
            return CommandResponse.failure(
                "Command execution interrupted",
                output
            );
        }
    }

    private static final class CollectingCommandSender
        implements CommandSender
    {

        private final CommandSender delegate;
        private final List<String> lines = new ArrayList<>();

        CollectingCommandSender(CommandSender delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void sendMessage(Message message) {
            if (message != null) {
                for (String line : extractLines(message)) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
            delegate.sendMessage(message);
        }

        @Override
        public String getDisplayName() {
            return delegate.getDisplayName();
        }

        @Override
        public UUID getUuid() {
            return delegate.getUuid();
        }

        @Override
        public boolean hasPermission(String permission) {
            return delegate.hasPermission(permission);
        }

        @Override
        public boolean hasPermission(String permission, boolean defaultValue) {
            return delegate.hasPermission(permission, defaultValue);
        }

        List<String> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }

        private static List<String> extractLines(Message message) {
            String ansiText = MessageUtil.toAnsiString(message).toString();
            if (ansiText != null && !ansiText.isEmpty()) {
                return splitLines(ansiText);
            }
            String raw = message.getRawText();
            if (raw != null && !raw.isEmpty()) {
                return splitLines(raw);
            }
            String fallback = message.toString();
            return fallback != null ? splitLines(fallback) : List.of();
        }

        private static List<String> splitLines(String text) {
            String[] parts = text.split("\\R", -1);
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                String sanitized = stripAnsi(part);
                result.add(sanitized.strip());
            }
            return result;
        }

        private static String stripAnsi(String text) {
            return ANSI_PATTERN.matcher(text).replaceAll("");
        }
    }
}
