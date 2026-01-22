package to.dstn.hytale.hyrcon;

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
import java.util.Optional;

public final class HyRconConfiguration {

    public static final String ENV_ENABLED = "HYRCON_ENABLED";
    public static final String ENV_BIND = "HYRCON_BIND";
    public static final String ENV_HOST = "HYRCON_HOST";
    public static final String ENV_PORT = "HYRCON_PORT";
    public static final String ENV_PASSWORD = "HYRCON_PASSWORD";
    public static final String ENV_PROTOCOL = "HYRCON_PROTOCOL";

    public static final String CONFIG_FILE_NAME = "config.yml";
    public static final String CONFIG_EXAMPLE_FILE_NAME = "config.yml.example";

    private static final String LEGACY_ENV_ENABLED = "RCON_ENABLED";
    private static final String LEGACY_ENV_BIND = "RCON_BIND";
    private static final String LEGACY_ENV_HOST = "RCON_HOST";
    private static final String LEGACY_ENV_PORT = "RCON_PORT";
    private static final String LEGACY_ENV_PASSWORD = "RCON_PASSWORD";
    private static final String LEGACY_ENV_PROTOCOL = "RCON_PROTOCOL";

    private static final String ENABLED_ENV_NAMES =
        ENV_ENABLED + "/" + LEGACY_ENV_ENABLED;
    private static final String BIND_ENV_NAMES =
        ENV_BIND + "/" + LEGACY_ENV_BIND;
    private static final String PORT_ENV_NAMES =
        ENV_PORT + "/" + LEGACY_ENV_PORT;
    private static final String PROTOCOL_ENV_NAMES =
        ENV_PROTOCOL + "/" + LEGACY_ENV_PROTOCOL;

    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 25575;
    public static final HyRconProtocol DEFAULT_PROTOCOL =
        HyRconProtocol.SOURCE_RCON;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final Optional<String> password;
    private final HyRconProtocol protocol;

    private HyRconConfiguration(
        boolean enabled,
        String host,
        int port,
        Optional<String> password,
        HyRconProtocol protocol
    ) {
        this.enabled = enabled;
        this.host = Objects.requireNonNull(host, "host");
        this.port = validatePort(port);
        this.password = Objects.requireNonNull(password, "password");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    public static HyRconConfiguration load(Path dataDirectory) {
        return load(dataDirectory, System.getenv());
    }

    public static HyRconConfiguration load(
        Path dataDirectory,
        Map<String, String> environment
    ) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(environment, "environment");

        Map<String, String> merged = new HashMap<>(environment);
        Map<String, String> overrides = readOverrides(dataDirectory);

        if (!overrides.isEmpty()) {
            merged.putAll(overrides);
        }

        return fromEnvironment(merged);
    }

    public static HyRconConfiguration fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static HyRconConfiguration fromEnvironment(
        Map<String, String> environment
    ) {
        Objects.requireNonNull(environment, "environment");

        boolean enabled = parseEnabled(
            firstValue(environment, ENV_ENABLED, LEGACY_ENV_ENABLED)
        );
        BindEndpoint bindEndpoint = parseBind(
            firstValue(environment, ENV_BIND, LEGACY_ENV_BIND)
        );
        HyRconProtocol protocol = parseProtocol(
            firstValue(environment, ENV_PROTOCOL, LEGACY_ENV_PROTOCOL)
        );
        String host = bindEndpoint
            .host()
            .map(HyRconConfiguration::sanitizeHost)
            .orElseGet(() ->
                sanitizeHost(firstValue(environment, ENV_HOST, LEGACY_ENV_HOST))
            );
        int port = bindEndpoint
            .port()
            .orElseGet(() ->
                parsePort(
                    firstValue(environment, ENV_PORT, LEGACY_ENV_PORT),
                    protocol.defaultPort()
                )
            );
        Optional<String> password = sanitizePassword(
            firstValue(environment, ENV_PASSWORD, LEGACY_ENV_PASSWORD)
        );

        return new HyRconConfiguration(enabled, host, port, password, protocol);
    }

    public boolean enabled() {
        return enabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public Optional<String> password() {
        return password;
    }

    public HyRconProtocol protocol() {
        return protocol;
    }

    public boolean isPasswordRequired() {
        return password.isPresent();
    }

    @Override
    public String toString() {
        return (
            "HyRconConfiguration{" +
            "enabled=" +
            enabled +
            ", host='" +
            host +
            '\'' +
            ", port=" +
            port +
            ", protocol=" +
            protocol +
            ", password=" +
            password.map(HyRconConfiguration::mask).orElse("<none>") +
            '}'
        );
    }

    private static Map<String, String> readOverrides(Path dataDirectory) {
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

            if (value.equalsIgnoreCase("null") || value.equals("~")) {
                value = "";
            }

            switch (key.toLowerCase(Locale.ROOT)) {
                case "enabled":
                    overrides.put(ENV_ENABLED, value);
                    overrides.put(LEGACY_ENV_ENABLED, value);
                    break;
                case "bind":
                    overrides.put(ENV_BIND, value);
                    overrides.put(LEGACY_ENV_BIND, value);
                    break;
                case "host":
                    overrides.put(ENV_HOST, value);
                    overrides.put(LEGACY_ENV_HOST, value);
                    break;
                case "port":
                    overrides.put(ENV_PORT, value);
                    overrides.put(LEGACY_ENV_PORT, value);
                    break;
                case "password":
                    overrides.put(ENV_PASSWORD, value);
                    overrides.put(LEGACY_ENV_PASSWORD, value);
                    break;
                case "protocol":
                    overrides.put(ENV_PROTOCOL, value);
                    overrides.put(LEGACY_ENV_PROTOCOL, value);
                    break;
                default:
                    break;
            }
        }

        return overrides;
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
            .append(newline);
        builder
            .append("# Set to false to disable the RCON listener.")
            .append(newline);
        builder.append("enabled: ").append(DEFAULT_ENABLED).append(newline);
        builder
            .append("# Host interface others connect through.")
            .append(newline);
        builder
            .append("host: \"")
            .append(DEFAULT_HOST)
            .append('\"')
            .append(newline);
        builder
            .append("# Network port that the listener will bind to.")
            .append(newline);
        builder.append("port: ").append(DEFAULT_PORT).append(newline);
        builder
            .append("# Protocol to use: hyrcon (legacy) or source.")
            .append(newline);
        builder
            .append("protocol: \"")
            .append(DEFAULT_PROTOCOL.configToken())
            .append('"')
            .append(newline);
        builder
            .append(
                "# Optional password; leave blank for no password requirement."
            )
            .append(newline);
        builder.append("password: \"\"").append(newline);

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
            return "sha256:" + hex.substring(0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                "Failed to compute HyRCON config template hash",
                ex
            );
        }
    }

    private static String stripInlineComment(String value) {
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '\"' && !inSingleQuotes) {
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
                (first == '\"' && last == '\"') ||
                (first == '\'' && last == '\'')
            ) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean parseEnabled(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_ENABLED;
        }

        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEFAULT_ENABLED;
        }

        switch (normalized) {
            case "1":
            case "true":
            case "yes":
            case "on":
                return true;
            case "0":
            case "false":
            case "no":
            case "off":
                return false;
            default:
                throw new IllegalArgumentException(
                    "Unsupported boolean value for " +
                        ENABLED_ENV_NAMES +
                        ": " +
                        rawValue
                );
        }
    }

    private static String sanitizeHost(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_HOST;
        }

        String trimmed = rawValue.trim();
        return trimmed.isEmpty() ? DEFAULT_HOST : trimmed;
    }

    private static int parsePort(String rawValue) {
        return parsePort(rawValue, DEFAULT_PORT);
    }

    private static int parsePort(String rawValue, int defaultPort) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return defaultPort;
        }

        return parsePortValue(rawValue.trim(), PORT_ENV_NAMES);
    }

    private static int parsePortValue(String rawValue, String variableNames) {
        if (rawValue == null || rawValue.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid numeric value for " + variableNames + ": " + rawValue
            );
        }

        try {
            return validatePort(Integer.parseInt(rawValue));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "Invalid numeric value for " + variableNames + ": " + rawValue,
                ex
            );
        }
    }

    private static Optional<String> sanitizePassword(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }

        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(trimmed);
    }

    private static HyRconProtocol parseProtocol(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return DEFAULT_PROTOCOL;
        }

        try {
            return HyRconProtocol.fromToken(rawValue);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unsupported protocol value for " +
                    PROTOCOL_ENV_NAMES +
                    ": " +
                    rawValue,
                ex
            );
        }
    }

    private static String firstValue(
        Map<String, String> environment,
        String... keys
    ) {
        for (String key : keys) {
            String value = environment.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BindEndpoint parseBind(String rawValue) {
        if (rawValue == null) {
            return BindEndpoint.empty();
        }

        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return BindEndpoint.empty();
        }

        String hostPart = null;
        String portPart = null;

        if (trimmed.startsWith("[")) {
            int closing = trimmed.indexOf(']');
            if (closing < 0) {
                throw new IllegalArgumentException(
                    "Invalid bind format for " +
                        BIND_ENV_NAMES +
                        ": " +
                        rawValue
                );
            }
            hostPart = trimmed.substring(1, closing);
            if (closing + 1 < trimmed.length()) {
                if (trimmed.charAt(closing + 1) != ':') {
                    throw new IllegalArgumentException(
                        "Invalid bind format for " +
                            BIND_ENV_NAMES +
                            ": " +
                            rawValue
                    );
                }
                portPart = trimmed.substring(closing + 2);
            }
        } else {
            int firstColon = trimmed.indexOf(':');
            int lastColon = trimmed.lastIndexOf(':');
            if (lastColon >= 0 && firstColon == lastColon) {
                hostPart = trimmed.substring(0, lastColon);
                portPart = trimmed.substring(lastColon + 1);
            } else if (lastColon == -1) {
                if (trimmed.chars().allMatch(Character::isDigit)) {
                    portPart = trimmed;
                } else {
                    hostPart = trimmed;
                }
            } else {
                hostPart = trimmed;
            }
        }

        String normalizedHost =
            hostPart == null || hostPart.trim().isEmpty()
                ? null
                : hostPart.trim();
        Integer normalizedPort = null;
        if (portPart != null && !portPart.trim().isEmpty()) {
            normalizedPort = Integer.valueOf(
                parsePortValue(portPart.trim(), BIND_ENV_NAMES)
            );
        }

        if (normalizedHost == null && normalizedPort == null) {
            return BindEndpoint.empty();
        }

        return new BindEndpoint(normalizedHost, normalizedPort);
    }

    private static final class BindEndpoint {

        private static final BindEndpoint EMPTY = new BindEndpoint(null, null);

        private final String host;
        private final Integer port;

        private BindEndpoint(String host, Integer port) {
            this.host = host;
            this.port = port;
        }

        static BindEndpoint empty() {
            return EMPTY;
        }

        Optional<String> host() {
            return Optional.ofNullable(host);
        }

        Optional<Integer> port() {
            return Optional.ofNullable(port);
        }
    }

    private static int validatePort(int candidate) {
        if (candidate < 1 || candidate > 65535) {
            throw new IllegalArgumentException(
                "Port must be in the range [1, 65535]"
            );
        }
        return candidate;
    }

    private static String mask(String value) {
        return "*".repeat(Math.min(value.length(), 8));
    }
}
