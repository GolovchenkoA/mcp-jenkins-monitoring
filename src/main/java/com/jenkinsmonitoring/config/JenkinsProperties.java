package com.jenkinsmonitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("jenkins")
public record JenkinsProperties(
        @DefaultValue List<Server> servers,
        @DefaultValue({"getBuild", "getJob", "whoAmI", "getStatus"}) List<String> allowedTools,
        String buildTree,
        @DefaultValue("10s") Duration requestTimeout) {

    /** One Jenkins MCP server. {@code auth} is the name of an environment variable, never the secret itself. */
    public record Server(String url, @DefaultValue("STREAMABLE") String protocol, String auth) {
    }
}
