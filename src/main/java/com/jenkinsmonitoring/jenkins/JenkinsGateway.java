package com.jenkinsmonitoring.jenkins;

import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.logging.McpCallLogger;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.storage.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * The single door to the Jenkins MCP servers. The collector, the rule validation and the health checks all
 * go through here, so every rule about talking to Jenkins is enforced in one place:
 * <ul>
 *   <li>only allowlisted read-only tools can be called, and the build-changing tools never can, whatever
 *       the configuration says;</li>
 *   <li>{@code getBuild} and {@code getJob} are refused without a {@code tree}, because an unfiltered
 *       build response is huge;</li>
 *   <li>each server's Authorization header is held here and nowhere else;</li>
 *   <li>the answer is normalized: "no results" becomes {@link JenkinsException.NotFound}, connection and
 *       credential problems become {@link JenkinsException.Unavailable}, an error reported by the server
 *       becomes {@link JenkinsException.ToolError};</li>
 *   <li>every call is logged in the debug profile.</li>
 * </ul>
 */
@Component
public class JenkinsGateway implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JenkinsGateway.class);
    private static final String CLIENT_VERSION = "0.1.0";
    private static final Set<String> TOOLS_REQUIRING_TREE = Set.of("getBuild", "getJob", "getJobs");
    /** These change builds. No configuration may enable them. */
    private static final Set<String> BUILD_CHANGING_TOOLS = Set.of("triggerBuild", "rebuildBuild", "replayBuild", "updateBuild");
    private static final Pattern AUTH_STATUS = Pattern.compile("\\b(401|403)\\b|unauthori[sz]ed|forbidden", Pattern.CASE_INSENSITIVE);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private final JenkinsProperties properties;
    private final McpCallLogger callLogger;
    private final ToolCatalog catalog;
    private final ConcurrentMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    // Connecting is slow network work, so it is serialized per server and never done inside the map
    private final ConcurrentMap<String, Object> connectLocks = new ConcurrentHashMap<>();

    public JenkinsGateway(JenkinsProperties properties, McpCallLogger callLogger, ToolCatalog catalog) {
        this.properties = properties;
        this.callLogger = callLogger;
        this.catalog = catalog;
        properties.allowedTools().stream().filter(BUILD_CHANGING_TOOLS::contains).forEach(tool ->
                log.error("jenkins.allowed-tools lists {}, which changes builds. It is ignored and can never be called.", tool));
    }

    /** The build with the given number, or the latest build when {@code buildNumber} is null. */
    public JsonNode getBuild(JenkinsServer server, String job, Integer buildNumber) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("jobFullName", job);
        if (buildNumber != null) {
            arguments.put("buildNumber", buildNumber);
        }
        arguments.put("tree", properties.buildTree());
        return call(server, "getBuild", arguments);
    }

    public JsonNode getJob(JenkinsServer server, String job, String tree) {
        return call(server, "getJob", Map.of("jobFullName", job, "tree", tree));
    }

    /** Proves the server is reachable and accepts our credentials. */
    public JsonNode whoAmI(JenkinsServer server) {
        return call(server, "whoAmI", Map.of());
    }

    public JsonNode getStatus(JenkinsServer server) {
        return call(server, "getStatus", Map.of());
    }

    /** Connects (if needed) and learns the server's tools. Used at startup so problems show up early. */
    public void connect(JenkinsServer server) {
        try {
            client(server);
        } catch (RuntimeException e) {
            throw unavailable(server, e);
        }
    }

    JsonNode call(JenkinsServer server, String tool, Map<String, Object> arguments) {
        requireAllowed(tool);
        requireTree(tool, arguments);
        McpSyncClient client;
        try {
            client = client(server);
        } catch (RuntimeException e) {
            // Whatever went wrong while connecting, the server is not usable right now
            JenkinsException.Unavailable unavailable = unavailable(server, e);
            callLogger.outgoing(server.canonical(), tool, arguments, "FAILED: " + unavailable.getMessage());
            throw unavailable;
        }
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (McpError e) {
            // The server answered with a protocol error (unknown tool, bad arguments): the connection is fine
            callLogger.outgoing(server.canonical(), tool, arguments, "ERROR: " + e.getMessage());
            throw new JenkinsException.ToolError(tool + " was rejected by " + server.canonical() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // Drop this connection so the next call starts a fresh one instead of reusing a broken session
            clients.remove(server.canonical(), client);
            close(client);
            JenkinsException.Unavailable unavailable = unavailable(server, e);
            callLogger.outgoing(server.canonical(), tool, arguments, "FAILED: " + unavailable.getMessage());
            throw unavailable;
        }
        String payload = payloadOf(result);
        callLogger.outgoing(server.canonical(), tool, arguments, payload);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new JenkinsException.ToolError(tool + " reported an error: " + abbreviate(payload));
        }
        return unwrap(tool, payload);
    }

    private void requireAllowed(String tool) {
        if (BUILD_CHANGING_TOOLS.contains(tool)) {
            throw new IllegalArgumentException("Jenkins tool '" + tool + "' changes builds and can never be called");
        }
        if (!properties.allowedTools().contains(tool)) {
            throw new IllegalArgumentException("Jenkins tool '" + tool + "' is not on the allowlist");
        }
    }

    private static void requireTree(String tool, Map<String, Object> arguments) {
        if (TOOLS_REQUIRING_TREE.contains(tool) && !(arguments.get("tree") instanceof String tree && !tree.isBlank())) {
            throw new IllegalArgumentException("Jenkins tool '" + tool + "' must be called with a tree");
        }
    }

    /**
     * Every Jenkins tool wraps its answer as {@code {status, message, result}}. "No results" comes back as
     * COMPLETED without a {@code result}.
     */
    private static JsonNode unwrap(String tool, String payload) {
        if (payload.isBlank()) {
            throw new JenkinsException.ToolError(tool + " returned an empty answer");
        }
        JsonNode envelope;
        try {
            envelope = Json.MAPPER.readTree(payload);
        } catch (RuntimeException e) {
            throw new JenkinsException.ToolError(tool + " returned text that is not JSON: " + abbreviate(payload));
        }
        if (envelope.isMissingNode()) {
            throw new JenkinsException.ToolError(tool + " returned an empty answer");
        }
        if (!envelope.isObject() || !envelope.has("status")) {
            return envelope;
        }
        String status = envelope.path("status").asText();
        String message = envelope.path("message").asText("");
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            throw new JenkinsException.ToolError(tool + " ended with status " + status + ": " + message);
        }
        JsonNode result = envelope.get("result");
        if (result == null || result.isNull()) {
            throw new JenkinsException.NotFound(tool + ": " + (message.isEmpty() ? "no results" : message));
        }
        return result;
    }

    private static String payloadOf(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) {
            return Json.COMPACT.writeValueAsString(result.structuredContent());
        }
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    return text.text();
                }
            }
        }
        return "";
    }

    private McpSyncClient client(JenkinsServer server) {
        McpSyncClient existing = clients.get(server.canonical());
        if (existing != null) {
            return existing;
        }
        synchronized (connectLocks.computeIfAbsent(server.canonical(), key -> new Object())) {
            existing = clients.get(server.canonical());
            if (existing != null) {
                return existing;
            }
            McpSyncClient created = create(server);
            try {
                created.initialize();
            } catch (RuntimeException e) {
                close(created);
                throw e;
            }
            refreshCatalog(server, created);
            clients.put(server.canonical(), created);
            return created;
        }
    }

    private McpSyncClient create(JenkinsServer server) {
        Duration timeout = properties.requestTimeout();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(server.canonical())
                .endpoint(server.endpoint())
                .connectTimeout(timeout)
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.setHeader("Authorization", server.authHeader().value()))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("jenkins-monitoring-mcp", CLIENT_VERSION))
                .build();
    }

    /** Learns the tools of a server and warns when an allowlisted tool is missing or cannot take a tree. */
    private void refreshCatalog(JenkinsServer server, McpSyncClient client) {
        try {
            List<ToolCatalog.ToolInfo> tools = client.listTools().tools().stream()
                    .map(tool -> new ToolCatalog.ToolInfo(tool.name(), tool.description(), acceptsTree(tool)))
                    .toList();
            catalog.update(server.canonical(), tools);
            for (String allowed : properties.allowedTools()) {
                ToolCatalog.ToolInfo info = tools.stream().filter(t -> t.name().equals(allowed)).findFirst().orElse(null);
                if (info == null) {
                    log.warn("Jenkins server {} does not offer the allowlisted tool {}", server.canonical(), allowed);
                } else if (TOOLS_REQUIRING_TREE.contains(allowed) && !info.acceptsTree()) {
                    log.warn("Jenkins tool {} on {} does not accept a tree", allowed, server.canonical());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not list the tools of {}: {}", server.canonical(), e.getMessage());
        }
    }

    private static boolean acceptsTree(McpSchema.Tool tool) {
        return tool.inputSchema() != null
                && tool.inputSchema().get("properties") instanceof Map<?, ?> properties
                && properties.containsKey("tree");
    }

    private static JenkinsException.Unavailable unavailable(JenkinsServer server, RuntimeException cause) {
        return new JenkinsException.Unavailable(server.canonical() + " is not available: " + describe(cause),
                isAuthFailure(cause), cause);
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return root == error ? text : text + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    private static boolean isAuthFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof McpHttpClientTransportAuthorizationException
                    || AUTH_STATUS.matcher(String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT)).find()) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    /** Closing may try to reach a dead server, so it never gets more than a moment. */
    private static void close(McpSyncClient client) {
        awaitAll(List.of(startClosing(client)));
    }

    private static Thread startClosing(McpSyncClient client) {
        return Thread.startVirtualThread(() -> {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("Ignoring error while closing a Jenkins MCP client: {}", e.getMessage());
            }
        });
    }

    /** Waits for all of them together, at most {@link #CLOSE_TIMEOUT} in total. */
    private static void awaitAll(List<Thread> closers) {
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        for (Thread closer : closers) {
            try {
                closer.join(Duration.ofNanos(Math.max(deadline - System.nanoTime(), 0)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void destroy() {
        awaitAll(clients.values().stream().map(JenkinsGateway::startClosing).toList());
        clients.clear();
    }
}
