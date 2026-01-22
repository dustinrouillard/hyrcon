package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class HyRCON extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final PlaceholderCommandExecutor commandExecutor =
        new PlaceholderCommandExecutor();
    private HyRconConfiguration configuration;
    private HyRconServer hyRconServer;
    private Thread shutdownHook;
    private boolean shutdownHookRegistered;

    public HyRCON(@NotNull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("HyRCON plugin initialized");
    }

    @Override
    protected void start() {
        super.start();
        commandExecutor.reset();

        try {
            configuration = loadConfiguration(getDataDirectory());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            LOGGER.atInfo().log(
                "Failed to load HyRCON configuration: %s",
                ex.getMessage()
            );
            configuration = null;
            return;
        }

        LOGGER.atInfo().log(
            "HyRCON configuration: enabled=%s host=%s port=%d protocol=%s password=%s",
            configuration.enabled(),
            configuration.host(),
            configuration.port(),
            configuration.protocol().configToken(),
            configuration.isPasswordRequired() ? "required" : "optional"
        );

        if (shutdownHookRegistered && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }
        shutdownHook = null;
        shutdownHookRegistered = false;

        if (hyRconServer != null) {
            hyRconServer.stop();
            hyRconServer = null;
        }

        if (!configuration.enabled()) {
            LOGGER.atInfo().log(
                "HyRCON listener disabled via configuration overrides"
            );
            return;
        }

        hyRconServer = new HyRconServer(configuration, commandExecutor);
        bootstrapCommandExecutor();
        hyRconServer.start();

        Thread hook = new Thread(
            () -> {
                HyRconServer server = hyRconServer;
                if (server != null) {
                    server.stop();
                }
            },
            "hyrcon-shutdown"
        );
        shutdownHook = hook;

        if (hyRconServer.isRunning()) {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHookRegistered = true;
            LOGGER.atInfo().log("HyRCON server started");
            if (!commandExecutor.isReady()) {
                LOGGER.atInfo().log(
                    "Command dispatcher delegate not installed yet; HyRCON commands will fail until wired"
                );
            }
        } else {
            LOGGER.atInfo().log(
                "HyRCON server failed to start; see previous logs for details"
            );
        }
    }

    private HyRconConfiguration loadConfiguration(Path dataDirectory) {
        Map<String, String> overrides = ConfigFile.load(dataDirectory);
        if (overrides.isEmpty()) {
            return HyRconConfiguration.fromEnvironment();
        }

        Map<String, String> merged = new HashMap<>(System.getenv());
        merged.putAll(overrides);
        return HyRconConfiguration.fromEnvironment(merged);
    }

    private void bootstrapCommandExecutor() {
        if (commandExecutor.isReady()) {
            return;
        }

        try {
            DispatcherCommandExecutor dispatcher =
                new DispatcherCommandExecutor();
            commandExecutor.install(dispatcher);
            LOGGER.atInfo().log(
                "Command dispatcher delegate installed: %s",
                dispatcher.getClass().getName()
            );
        } catch (RuntimeException ex) {
            LOGGER.atInfo().log(
                "Failed to initialize command dispatcher delegate: %s",
                ex.toString()
            );
        }
    }

    @Override
    protected void shutdown() {
        if (shutdownHookRegistered && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }

        shutdownHook = null;
        shutdownHookRegistered = false;

        if (hyRconServer != null) {
            hyRconServer.stop();
            hyRconServer = null;
        }

        commandExecutor.reset();
        configuration = null;
        LOGGER.atInfo().log("HyRCON plugin disabled");

        super.shutdown();
    }

    private static final class ConfigFile {

        private static final String CONFIG_FILE_NAME = "config.yml";
        private static final String CONFIG_EXAMPLE_FILE_NAME =
            "config.yml.example";

        private ConfigFile() {}

        static Map<String, String> load(Path dataDirectory) {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            ensureExampleConfiguration(dataDirectory);

            Path configPath = dataDirectory.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configPath) || Files.isDirectory(configPath)) {
                return Collections.emptyMap();
            }

            List<String> lines;
            try {
                lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException(
                    "Failed to read HyRCON configuration file: " + configPath,
                    ex
                );
            }

            Map<String, String> overrides = new HashMap<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separator = line.indexOf(':');
                if (separator < 0) {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                value = stripInlineComment(value);
                value = stripQuotes(value).trim();

                if (isNullToken(value)) {
                    value = "";
                }

                switch (key.toLowerCase(Locale.ROOT)) {
                    case "enabled":
                        putOverride(
                            overrides,
                            HyRconConfiguration.ENV_ENABLED,
                            ConfigFileConstants.LEGACY_ENV_ENABLED,
                            value
                        );
                        break;
                    case "host":
                        putOverride(
                            overrides,
                            HyRconConfiguration.ENV_HOST,
                            ConfigFileConstants.LEGACY_ENV_HOST,
                            value
                        );
                        break;
                    case "port":
                        putOverride(
                            overrides,
                            HyRconConfiguration.ENV_PORT,
                            ConfigFileConstants.LEGACY_ENV_PORT,
                            value
                        );
                        break;
                    case "password":
                        putOverride(
                            overrides,
                            HyRconConfiguration.ENV_PASSWORD,
                            ConfigFileConstants.LEGACY_ENV_PASSWORD,
                            value
                        );
                        break;
                    case "protocol":
                        putOverride(
                            overrides,
                            HyRconConfiguration.ENV_PROTOCOL,
                            ConfigFileConstants.LEGACY_ENV_PROTOCOL,
                            value
                        );
                        break;
                    default:
                        break;
                }
            }

            return overrides;
        }

        private static void putOverride(
            Map<String, String> overrides,
            String primary,
            String legacy,
            String value
        ) {
            overrides.put(primary, value);
            overrides.put(legacy, value);
        }

        private static boolean isNullToken(String value) {
            if (value == null) {
                return true;
            }
            String trimmed = value.trim();
            return (
                trimmed.isEmpty() ||
                "~".equals(trimmed) ||
                "null".equalsIgnoreCase(trimmed)
            );
        }

        private static String stripInlineComment(String value) {
            boolean inSingleQuotes = false;
            boolean inDoubleQuotes = false;

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\'' && !inDoubleQuotes) {
                    inSingleQuotes = !inSingleQuotes;
                } else if (c == '"' && !inSingleQuotes) {
                    inDoubleQuotes = !inDoubleQuotes;
                } else if (c == '#' && !inSingleQuotes && !inDoubleQuotes) {
                    if (i == 0 || Character.isWhitespace(value.charAt(i - 1))) {
                        return value.substring(0, i).trim();
                    }
                }
            }

            return value.trim();
        }

        private static String stripQuotes(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if (
                    (first == '"' && last == '"') ||
                    (first == '\'' && last == '\'')
                ) {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }

        private static void ensureExampleConfiguration(Path dataDirectory) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException ex) {
                throw new IllegalStateException(
                    "Failed to create HyRCON data directory: " + dataDirectory,
                    ex
                );
            }

            Path examplePath = dataDirectory.resolve(CONFIG_EXAMPLE_FILE_NAME);
            if (Files.exists(examplePath)) {
                return;
            }

            String newline = System.lineSeparator();
            StringBuilder builder = new StringBuilder();
            builder
                .append("# HyRCON configuration example")
                .append(newline)
                .append(
                    "# Copy this file to " +
                        CONFIG_FILE_NAME +
                        " to override environment variables."
                )
                .append(newline)
                .append("# Set to false to disable the RCON listener.")
                .append(newline)
                .append("enabled: ")
                .append(HyRconConfiguration.DEFAULT_ENABLED)
                .append(newline)
                .append("# Host interface others connect through.")
                .append(newline)
                .append("host: \"")
                .append(HyRconConfiguration.DEFAULT_HOST)
                .append('\"')
                .append(newline)
                .append("# Network port that the listener will bind to.")
                .append(newline)
                .append("port: ")
                .append(HyRconConfiguration.DEFAULT_PORT)
                .append(newline)
                .append("# Protocol to use: hyrcon (legacy) or source.")
                .append(newline)
                .append("protocol: \"")
                .append(HyRconConfiguration.DEFAULT_PROTOCOL.configToken())
                .append('\"')
                .append(newline)
                .append(
                    "# Optional password; leave blank for no password requirement."
                )
                .append(newline)
                .append(
                    "# If you're binding to 0.0.0.0 ensure you have proper firewall rules in place if you remove the password."
                )
                .append(newline)
                .append("password: \"hytale\"")
                .append(newline);

            String templateBody = builder.toString();
            String versionLine =
                "# configuration version: " +
                computeTemplateVersion(templateBody) +
                newline;
            String content = versionLine + templateBody;

            try {
                Files.writeString(examplePath, content, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException(
                    "Failed to write HyRCON example configuration file: " +
                        examplePath,
                    ex
                );
            }
        }

        private static String computeTemplateVersion(String content) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(
                    content.getBytes(StandardCharsets.UTF_8)
                );
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                String fullHash = hex.toString();
                return "sha256:" + fullHash.substring(0, 12);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(
                    "Failed to compute HyRCON config template hash",
                    ex
                );
            }
        }

        private static final class ConfigFileConstants {

            private static final String LEGACY_ENV_ENABLED = "RCON_ENABLED";

            private static final String LEGACY_ENV_HOST = "RCON_HOST";
            private static final String LEGACY_ENV_PORT = "RCON_PORT";
            private static final String LEGACY_ENV_PASSWORD = "RCON_PASSWORD";
            private static final String LEGACY_ENV_PROTOCOL = "RCON_PROTOCOL";

            private ConfigFileConstants() {}
        }
    }
}
