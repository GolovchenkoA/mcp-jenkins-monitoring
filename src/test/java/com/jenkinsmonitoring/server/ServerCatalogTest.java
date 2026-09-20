package com.jenkinsmonitoring.server;

import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ServerCatalogTest {

    private static ServerCatalog catalog(Function<String, String> environment, JenkinsProperties.Server... servers) {
        return new ServerCatalog(TestServers.properties(servers), environment);
    }

    @Test
    void aValidServerIsUsable() {
        ServerCatalog catalog = catalog(TestServers.environment(), TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.problems()).isEmpty();
        JenkinsServer server = catalog.servers().get(0);
        assertThat(server.canonical()).isEqualTo("https://jenkins-server1.com");
        assertThat(server.endpoint()).isEqualTo("/mcp-server/mcp");
        assertThat(server.authHeader().value()).isEqualTo(TestServers.AUTH_VALUE);
    }

    @Test
    void theSecretNeverShowsInToString() {
        ServerCatalog catalog = catalog(TestServers.environment(), TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.servers().get(0).toString()).doesNotContain("dGVzdDp0ZXN0");
    }

    @Test
    void userInputResolvesToTheConfiguredServer() {
        ServerCatalog catalog = catalog(TestServers.environment(), TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.resolve("jenkins-server1.com")).isPresent();
        assertThat(catalog.resolve("https://jenkins-server1.com")).isPresent();
        assertThat(catalog.resolve("other.com")).isEmpty();
    }

    @Test
    void aMissingEnvironmentVariableIsAProblemNamingTheVariable() {
        ServerCatalog catalog = catalog(name -> null, TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.server()).isEqualTo("https://jenkins-server1.com");
            assertThat(problem.message()).contains(TestServers.AUTH_VAR);
        });
    }

    @Test
    void aLiteralCredentialInTheFileIsRejectedAndNeverEchoed() {
        JenkinsProperties.Server literal = new JenkinsProperties.Server("https://jenkins-server1.com/mcp-server/mcp",
                "STREAMABLE", "Basic dXNlcjpzZWNyZXQ=");

        ServerCatalog catalog = catalog(TestServers.environment(), literal);

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message()).doesNotContain("dXNlcjpzZWNyZXQ=");
    }

    @Test
    void aMalformedHeaderIsAProblem() {
        Function<String, String> environment = Map.of(TestServers.AUTH_VAR, "Bearer abc")::get;

        ServerCatalog catalog = catalog(environment, TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message()).contains("Basic <base64>");
    }

    @Test
    void anUnknownProtocolIsAProblem() {
        JenkinsProperties.Server sse = new JenkinsProperties.Server("https://jenkins-server1.com/mcp-server/mcp",
                "SSE", TestServers.AUTH_VAR);

        assertThat(catalog(TestServers.environment(), sse).problems().get(0).message()).contains("STREAMABLE");
    }

    @Test
    void protocolIsCaseInsensitive() {
        JenkinsProperties.Server lower = new JenkinsProperties.Server("https://jenkins-server1.com/mcp-server/mcp",
                "streamable", TestServers.AUTH_VAR);

        assertThat(catalog(TestServers.environment(), lower).servers()).hasSize(1);
    }

    @Test
    void twoServersWithTheSameCanonicalUrlAreNotAllowed() {
        ServerCatalog catalog = catalog(TestServers.environment(),
                TestServers.server("https://jenkins-server1.com/mcp-server/mcp"),
                TestServers.server("https://JENKINS-server1.com/other/mcp"));

        assertThat(catalog.servers()).hasSize(1);
        assertThat(catalog.problems().get(0).message()).contains("same canonical URL");
    }

    @Test
    void aServerWithAProblemStillResolvesForRemoval() {
        ServerCatalog catalog = catalog(name -> null, TestServers.server("https://jenkins-server1.com/mcp-server/mcp"));

        assertThat(catalog.resolve("jenkins-server1.com")).isEmpty();
        assertThat(catalog.resolveConfigured("jenkins-server1.com")).contains("https://jenkins-server1.com");
    }

    @Test
    void anInvalidUrlIsAProblem() {
        ServerCatalog catalog = catalog(TestServers.environment(), TestServers.server("jenkins-server1.com"));

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).server()).isEqualTo("jenkins.servers[0]");
    }
}
