package com.jenkinsmonitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Jenkins access settings. {@code servers} is bound from {@code jenkins.server[n]} (singular, so the
 * environment variables are {@code JENKINS_SERVER_0_URL} and so on). It is a map keyed by the number, not a
 * list: Spring Boot merges maps across sources and does not mind gaps in the numbers, so the servers can come
 * partly from an application.properties and partly from environment variables such as
 * {@code JENKINS_SERVER_1_URL}.
 */
@ConfigurationProperties("jenkins")
public record JenkinsProperties(
        @Name("server") @DefaultValue Map<Integer, Server> servers,
        @DefaultValue({"getBuild", "getJob", "whoAmI", "getStatus"}) List<String> allowedTools,
        String buildTree,
        @DefaultValue("10s") Duration requestTimeout) {

    /** One Jenkins MCP server. {@code auth} is the name of an environment variable, never the secret itself. */
    public record Server(String url, @DefaultValue("STREAMABLE") String protocol, String auth) {
    }
}
