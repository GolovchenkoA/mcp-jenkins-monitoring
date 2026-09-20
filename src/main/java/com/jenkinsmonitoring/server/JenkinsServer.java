package com.jenkinsmonitoring.server;

import java.net.URI;

/**
 * A usable, validated Jenkins MCP server.
 *
 * @param canonical  {@code scheme://host[:port]}, the identity of the server
 * @param endpoint   path of the MCP endpoint on that server, for example {@code /mcp-server/mcp}
 * @param authEnvVar name of the environment variable that holds the Authorization header value
 * @param authHeader that header value, {@code Basic <base64>}
 */
public record JenkinsServer(String canonical, String endpoint, String authEnvVar, Secret authHeader) {

    public String host() {
        return URI.create(canonical).getHost();
    }
}
