package com.jenkinsmonitoring.jenkins;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * What each Jenkins MCP server says it offers, learned with {@code tools/list} each time a connection is
 * made. It lives in memory only: it is rebuilt at every start, so it can never be stale after a restart.
 */
@Component
public class ToolCatalog {

    public record ToolInfo(String name, String description, boolean acceptsTree) {
    }

    private final ConcurrentMap<String, List<ToolInfo>> byServer = new ConcurrentHashMap<>();

    public void update(String server, List<ToolInfo> tools) {
        byServer.put(server, List.copyOf(tools));
    }

    public List<ToolInfo> toolsOf(String server) {
        return byServer.getOrDefault(server, List.of());
    }
}
