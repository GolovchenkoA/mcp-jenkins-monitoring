package com.jenkinsmonitoring.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The canonical form of a server is {@code scheme://host[:port]}: no path, lowercase host, no trailing
 * slash, default port omitted. It is the only form used where a value identifies just a server.
 */
public final class ServerUrls {

    private ServerUrls() {
    }

    /** The canonical URL of a URL or a bare host name. Without a scheme https is assumed. */
    public static Optional<String> canonicalOf(String input) {
        return parse(input).map(ServerUrls::origin);
    }

    /**
     * host[:port] of the input, used to match what a user typed to a configured server. Scheme and path are
     * ignored, so {@code jenkins.example.com} and {@code https://jenkins.example.com/mcp-server/mcp} match.
     */
    public static Optional<String> hostKey(String input) {
        return canonicalOf(input).map(canonical -> canonical.substring(canonical.indexOf("://") + 3));
    }

    /** The path (and query) of a configured MCP URL, for example {@code /mcp-server/mcp}. */
    public static Optional<String> endpointOf(String input) {
        return parse(input).map(uri -> {
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                return "/mcp";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        });
    }

    private static Optional<URI> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String text = input.trim();
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            boolean supported = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return supported && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (port == 80 && scheme.equals("http"))
                || (port == 443 && scheme.equals("https"));
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
