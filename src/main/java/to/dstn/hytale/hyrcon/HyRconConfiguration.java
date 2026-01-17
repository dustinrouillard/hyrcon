package to.dstn.hytale.hyrcon;

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

    private static final String LEGACY_ENV_ENABLED = "RCON_ENABLED";
    private static final String LEGACY_ENV_BIND = "RCON_BIND";
    private static final String LEGACY_ENV_HOST = "RCON_HOST";
    private static final String LEGACY_ENV_PORT = "RCON_PORT";
    private static final String LEGACY_ENV_PASSWORD = "RCON_PASSWORD";

    private static final String ENABLED_ENV_NAMES =
        ENV_ENABLED + "/" + LEGACY_ENV_ENABLED;
    private static final String BIND_ENV_NAMES =
        ENV_BIND + "/" + LEGACY_ENV_BIND;
    private static final String PORT_ENV_NAMES =
        ENV_PORT + "/" + LEGACY_ENV_PORT;

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 5522;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final Optional<String> password;

    private HyRconConfiguration(
        boolean enabled,
        String host,
        int port,
        Optional<String> password
    ) {
        this.enabled = enabled;
        this.host = Objects.requireNonNull(host, "host");
        this.port = validatePort(port);
        this.password = Objects.requireNonNull(password, "password");
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
        String host = bindEndpoint
            .host()
            .map(HyRconConfiguration::sanitizeHost)
            .orElseGet(() ->
                sanitizeHost(firstValue(environment, ENV_HOST, LEGACY_ENV_HOST))
            );
        int port = bindEndpoint
            .port()
            .orElseGet(() ->
                parsePort(firstValue(environment, ENV_PORT, LEGACY_ENV_PORT))
            );
        Optional<String> password = sanitizePassword(
            firstValue(environment, ENV_PASSWORD, LEGACY_ENV_PASSWORD)
        );

        return new HyRconConfiguration(enabled, host, port, password);
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
            ", password=" +
            password.map(HyRconConfiguration::mask).orElse("<none>") +
            '}'
        );
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
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return DEFAULT_PORT;
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
