package com.jenkinsmonitoring;

import com.jenkinsmonitoring.collector.Collector;
import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.jenkins.JenkinsException;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.Secret;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts the whole application on a real port. A fake Jenkins MCP server runs inside the same application
 * (tools named like the real ones), so the real Streamable HTTP client of the gateway, the tool scanning and
 * the Authorization header are all exercised. The test itself talks to the application as an MCP client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        classes = {JenkinsMonitoringApplication.class, EndToEndTest.FakeJenkins.class, EndToEndTest.HeaderRecorder.class,
                EndToEndTest.TestEnvironment.class})
class EndToEndTest {

    private static final int PORT = freePort();
    private static final Path DATA = dataFolder();
    private static final String AUTH = "Basic dGVzdDp0ZXN0";
    private static final String JOB = "demo-job";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("files.root.folder", DATA::toString);
        registry.add("files.storage.path", () -> DATA.resolve("db").toString());
        registry.add("jenkins.servers[0].url", () -> "http://localhost:" + PORT + "/mcp");
        registry.add("jenkins.servers[0].protocol", () -> "STREAMABLE");
        registry.add("jenkins.servers[0].auth", () -> "E2E_AUTH");
        registry.add("scheduling.jenkins-job-check.cron", () -> "0 0 0 1 1 *");
        registry.add("scheduling.retention-cleanup.cron", () -> "0 0 0 1 1 *");
    }

    /** The Jenkins side: one job, builds set by the test. */
    @Component
    static class FakeJenkins {
        private static final String NO_RESULTS = "{\"status\":\"COMPLETED\",\"message\":\"Search completed, but no results were found for the specified criteria.\"}";

        final TreeMap<Integer, String> builds = new TreeMap<>(); // number -> result, null while running

        private static String ok(String result) {
            return "{\"status\":\"COMPLETED\",\"message\":\"Data retrieved successfully.\",\"result\":" + result + "}";
        }

        @McpTool(name = "whoAmI", description = "fake")
        public String whoAmI() {
            return ok("{\"fullName\":\"tester\"}");
        }

        /** What getStatus answers instead of the normal answer, to test how the gateway copes; null = normal. */
        volatile String statusAnswer;

        @McpTool(name = "getStatus", description = "fake")
        public String getStatus() {
            String answer = statusAnswer;
            if ("THROW".equals(answer)) {
                throw new IllegalStateException("boom");
            }
            return answer != null ? answer
                    : ok("{\"Quiet Mode\":false,\"Active administrative monitors\":[],\"Root URL Status\":\"OK\"}");
        }

        @McpTool(name = "getJob", description = "fake")
        public synchronized String getJob(@McpToolParam(description = "job") String jobFullName,
                                          @McpToolParam(required = false, description = "tree") String tree) {
            if (!JOB.equals(jobFullName)) {
                return NO_RESULTS;
            }
            int next = builds.isEmpty() ? 1 : builds.lastKey() + 1;
            return ok("{\"fullName\":\"" + JOB + "\",\"nextBuildNumber\":" + next + "}");
        }

        @McpTool(name = "getBuild", description = "fake")
        public synchronized String getBuild(@McpToolParam(description = "job") String jobFullName,
                                            @McpToolParam(required = false, description = "number") Integer buildNumber,
                                            @McpToolParam(required = false, description = "tree") String tree) {
            if (!JOB.equals(jobFullName) || builds.isEmpty()) {
                return NO_RESULTS;
            }
            Integer number = buildNumber == null ? builds.lastKey() : buildNumber;
            if (!builds.containsKey(number)) {
                return NO_RESULTS;
            }
            String result = builds.get(number);
            Integer next = builds.higherKey(number);
            return ok("{\"number\":" + number + ",\"url\":\"http://localhost:" + PORT + "/job/" + JOB + "/" + number + "/\","
                    + "\"building\":" + (result == null) + ",\"result\":" + (result == null ? "null" : "\"" + result + "\"") + ","
                    + "\"timestamp\":1789133991865,\"duration\":65000,"
                    + "\"nextBuild\":" + (next == null ? "null" : "{\"number\":" + next + "}") + ","
                    + "\"actions\":[{\"causes\":[{\"shortDescription\":\"Started by user\",\"userId\":\"artem.holovchenko\"}]},"
                    + "{\"parameters\":[{\"name\":\"Playbook\",\"value\":\"Azure Resources\"}]}]}");
        }
    }

    /** Credentials come from real environment variables, which a test cannot set, so it supplies its own lookup. */
    @TestConfiguration
    static class TestEnvironment {
        @Bean
        @Primary
        ServerCatalog serverCatalogWithTestEnvironment(JenkinsProperties properties) {
            return new ServerCatalog(properties, Map.of("E2E_AUTH", AUTH)::get);
        }
    }

    /** Remembers the Authorization header of every request that reaches the MCP endpoint. */
    @Component
    static class HeaderRecorder extends OncePerRequestFilter {
        final List<String> authorization = new CopyOnWriteArrayList<>();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null) {
                authorization.add(header);
            }
            if ("Basic YmFk".equals(header)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad credentials");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    @Autowired
    FakeJenkins jenkins;
    @Autowired
    HeaderRecorder headers;
    @Autowired
    Collector collector;
    @Autowired
    JenkinsGateway gateway;
    @Autowired
    ServerCatalog servers;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT).endpoint("/mcp").build()).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        jenkins.statusAnswer = null;
        client.close();
    }

    @AfterAll
    static void deleteData() throws IOException {
        try (Stream<Path> files = Files.walk(DATA)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static JsonNode json(CallToolResult result) {
        return Json.MAPPER.readTree(text(result));
    }

    @Test
    void theToolsAreListedAndReadOnlyOnesSaySo() {
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).contains("addRule", "removeRule", "listRules",
                "getRecentJobs", "getNotifications", "status");
        for (String readOnly : List.of("listRules", "getRecentJobs", "getNotifications", "status")) {
            assertThat(tools.stream().filter(t -> t.name().equals(readOnly)).findFirst().orElseThrow().annotations().readOnlyHint())
                    .as(readOnly).isTrue();
        }
        for (String changing : List.of("addRule", "removeRule")) {
            assertThat(tools.stream().filter(t -> t.name().equals(changing)).findFirst().orElseThrow().annotations().readOnlyHint())
                    .as(changing).isFalse();
        }
    }

    @Test
    void addRuleWithoutAJobExplainsWhatIsRequired() {
        CallToolResult result = call("addRule", Map.of("server", "localhost:" + PORT, "conditions", Map.of("user", "artem.holovchenko")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("requires both `server` and `job`").contains("Example");
    }

    @Test
    void aBrowserOriginOtherThanLocalhostIsRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/mcp"))
                .header("Origin", "https://evil.example.com")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void fromAddingARuleToReadingTheHistory() {
        jenkins.builds.clear();
        jenkins.builds.put(1, "SUCCESS");

        // A rule for a server written in a different form than it is configured
        CallToolResult added = call("addRule", Map.of("server", "localhost:" + PORT, "job", JOB,
                "conditions", Map.of("user", "artem.holovchenko", "playbook", List.of("Azure Resources", "other"))));
        assertThat(added.isError()).isFalse();
        assertThat(json(added).path("server").asText()).isEqualTo("http://localhost:" + PORT);
        assertThat(json(added).path("id").asInt()).isEqualTo(1);

        // Same server and job again: refused
        CallToolResult duplicate = call("addRule", Map.of("server", "http://localhost:" + PORT + "/", "job", JOB));
        assertThat(duplicate.isError()).isTrue();
        assertThat(text(duplicate)).contains("already exists");

        // Unknown job and invalid user are refused with an explanation
        assertThat(text(call("addRule", Map.of("server", "localhost:" + PORT, "job", "nope")))).contains("was not found");
        assertThat(text(call("addRule", Map.of("server", "localhost:" + PORT, "job", JOB,
                "conditions", Map.of("user", "Artem Holovchenko"))))).contains("name.surname");

        assertThat(json(call("listRules", Map.of())).size()).isEqualTo(1);

        // First pass remembers build 1; the next passes find the new builds
        collector.runOnce();
        assertThat(json(call("getRecentJobs", Map.of())).size()).isZero();
        jenkins.builds.put(2, "FAILURE");
        jenkins.builds.put(3, null);
        collector.runOnce();
        collector.runOnce();

        JsonNode recent = json(call("getRecentJobs", Map.of("limit", 5)));
        assertThat(recent.size()).isEqualTo(1);
        assertThat(recent.get(0).path("build_number").asInt()).isEqualTo(2);
        assertThat(recent.get(0).path("status").asText()).isEqualTo("FAILURE");
        assertThat(recent.get(0).path("triggered_by").asText()).isEqualTo("artem.holovchenko");
        assertThat(recent.get(0).path("playbook").asText()).isEqualTo("Azure Resources");
        assertThat(recent.get(0).path("url").asText()).isEqualTo("http://localhost:" + PORT + "/job/" + JOB + "/2/");

        jenkins.builds.put(3, "SUCCESS");
        collector.runOnce();
        assertThat(json(call("getRecentJobs", Map.of())).size()).isEqualTo(2);

        JsonNode pending = json(call("getNotifications", Map.of("status", "pending")));
        assertThat(pending.path("total").asInt()).isEqualTo(2);
        assertThat(pending.path("notifications").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(json(call("getNotifications", Map.of("status", "DELIVERED"))).path("total").asInt()).isZero();
        assertThat(json(call("getNotifications", Map.of())).path("total").asInt()).isEqualTo(2);
        assertThat(call("getNotifications", Map.of("status", "bogus")).isError()).isTrue();

        // The gateway called the fake Jenkins with the configured Authorization header
        assertThat(headers.authorization).contains(AUTH);

        // Status: the fake Jenkins answers, storage is fine
        JsonNode status = json(call("status", Map.of()));
        List<String> checks = new ArrayList<>();
        status.path("checks").forEach(check -> checks.add(check.path("name").asText() + "=" + check.path("status").asText()));
        assertThat(checks).contains("storage=OK", "collector=OK", "jenkins servers available=OK");
        assertThat(status.path("status").asText()).isEqualTo("OK");

        // Removing needs only the configuration
        assertThat(call("removeRule", Map.of("server", "localhost:" + PORT, "job", JOB)).isError()).isFalse();
        assertThat(json(call("listRules", Map.of())).size()).isZero();
        assertThat(text(call("removeRule", Map.of("server", "localhost:" + PORT, "job", JOB)))).contains("Rule not found");
        // History stays after the rule is removed
        assertThat(json(call("getRecentJobs", Map.of())).size()).isEqualTo(2);
    }

    @Test
    void theGatewayMapsEveryKindOfAnswerAndKeepsWorkingAfterwards() {
        JenkinsServer server = servers.servers().get(0);

        jenkins.statusAnswer = "this is not json";
        assertThatThrownBy(() -> gateway.getStatus(server)).isInstanceOf(JenkinsException.ToolError.class).hasMessageContaining("not JSON");
        jenkins.statusAnswer = "";
        assertThatThrownBy(() -> gateway.getStatus(server)).isInstanceOf(JenkinsException.ToolError.class).hasMessageContaining("empty");
        jenkins.statusAnswer = "{\"status\":\"FAILED\",\"message\":\"nope\"}";
        assertThatThrownBy(() -> gateway.getStatus(server)).isInstanceOf(JenkinsException.ToolError.class).hasMessageContaining("FAILED");
        jenkins.statusAnswer = "THROW";
        assertThatThrownBy(() -> gateway.getStatus(server)).isInstanceOf(JenkinsException.ToolError.class);
        assertThatThrownBy(() -> gateway.getJob(server, "no-such-job", "fullName")).isInstanceOf(JenkinsException.NotFound.class);

        // None of these was a connection problem, so the connection is still good
        jenkins.statusAnswer = null;
        assertThat(gateway.getStatus(server).path("Root URL Status").asText()).isEqualTo("OK");
        assertThat(gateway.whoAmI(server).path("fullName").asText()).isEqualTo("tester");
    }

    @Test
    void rejectedCredentialsAreReportedAsAnAuthenticationFailure() {
        // A different host name than the configured server, so the gateway makes a new connection with these credentials
        JenkinsServer badCredentials = new JenkinsServer("http://127.0.0.1:" + PORT, "/mcp", "X", new Secret("Basic YmFk"));

        assertThatThrownBy(() -> gateway.whoAmI(badCredentials))
                .isInstanceOfSatisfying(JenkinsException.Unavailable.class, e -> assertThat(e.isAuthFailure()).isTrue());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path dataFolder() {
        try {
            return Files.createTempDirectory("jenkins-monitoring-e2e");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
