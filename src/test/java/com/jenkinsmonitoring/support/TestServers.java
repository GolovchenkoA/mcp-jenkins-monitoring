package com.jenkinsmonitoring.support;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.server.ServerCatalog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class TestServers {

    public static final String AUTH_VAR = "TEST_AUTH";
    public static final String AUTH_VALUE = "Basic dGVzdDp0ZXN0";
    public static final String SERVER = "https://jenkins.example.com";

    private TestServers() {
    }

    public static JenkinsProperties properties(JenkinsProperties.Server... servers) {
        Map<Integer, JenkinsProperties.Server> byNumber = new LinkedHashMap<>();
        for (int i = 0; i < servers.length; i++) {
            byNumber.put(i, servers[i]);
        }
        return new JenkinsProperties(byNumber, List.of("getBuild", "getJob", "whoAmI", "getStatus"),
                "number,result", Duration.ofSeconds(5));
    }

    public static JenkinsProperties.Server server(String url) {
        return new JenkinsProperties.Server(url, "STREAMABLE", AUTH_VAR);
    }

    /** An environment in which only {@link #AUTH_VAR} is set. */
    public static Function<String, String> environment() {
        return Map.of(AUTH_VAR, AUTH_VALUE)::get;
    }

    /** A catalog with the one usable server {@link #SERVER}. */
    public static ServerCatalog catalog() {
        return new ServerCatalog(properties(server(SERVER + "/mcp-server/mcp")), environment());
    }

    public static AppProperties.Files files(Path folder) {
        return new AppProperties.Files(new AppProperties.Files.Root(folder),
                new AppProperties.Files.Storage(folder.resolve("db")));
    }
}
