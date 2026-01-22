package to.dstn.hytale.hyrcon;

import java.util.Locale;
import java.util.Objects;

/**
 * Enumerates the supported remote console protocols.
 *
 * HyRCON originally shipped with a simple line-oriented text protocol. To
 * support interoperability with existing tooling, we also expose a Source
 * compatible RCON mode that adheres to Valve's implementation. This enum allows
 * the server and configuration layers to choose between these behaviours.
 */
public enum HyRconProtocol {
    /**
     * The legacy HyRCON text protocol. Clients must send plain-text commands
     * terminated by newlines and optionally authenticate with the {@code AUTH}
     * verb.
     */
    HYRCON("hyrcon", 5522, false),

    /**
     * Source RCON protocol compatible with Valve's specification.
     */
    SOURCE_RCON("source", 25575, true);

    private final String configToken;
    private final int defaultPort;
    private final boolean sourceCompatible;

    HyRconProtocol(
        String configToken,
        int defaultPort,
        boolean sourceCompatible
    ) {
        this.configToken = normalize(
            Objects.requireNonNull(configToken, "configToken")
        );
        this.defaultPort = defaultPort;
        this.sourceCompatible = sourceCompatible;
    }

    /**
     * Returns the canonical token that should be used in configuration files or
     * environment variables to select this protocol.
     *
     * @return configuration token
     */
    public String configToken() {
        return configToken;
    }

    /**
     * Returns the default port typically used by this protocol. Consumers are
     * free to override the port explicitly, but this value can be used as a
     * sensible fallback.
     *
     * @return default listening port
     */
    public int defaultPort() {
        return defaultPort;
    }

    /**
     * Indicates whether this protocol is byte-for-byte compatible with the
     * Source RCON implementation.
     *
     * @return {@code true} if the protocol matches Source RCON semantics
     */
    public boolean isSourceCompatible() {
        return sourceCompatible;
    }

    /**
     * Attempts to resolve a protocol from a user-supplied token. Comparison is
     * case-insensitive and falls back to the Source-compatible protocol if the
     * input is {@code null} or blank.
     *
     * @param rawToken candidate token
     * @return matching protocol, never {@code null}
     * @throws IllegalArgumentException if the token does not map to a protocol
     */
    public static HyRconProtocol fromToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return SOURCE_RCON;
        }

        String normalized = normalize(rawToken);
        for (HyRconProtocol protocol : values()) {
            if (protocol.configToken.equals(normalized)) {
                return protocol;
            }
        }

        throw new IllegalArgumentException(
            "Unknown HyRCON protocol: " + rawToken
        );
    }

    private static String normalize(String token) {
        return Objects.requireNonNull(token, "token")
            .trim()
            .toLowerCase(Locale.ROOT);
    }
}
