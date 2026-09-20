package com.jenkinsmonitoring.jenkins;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.logging.McpCallLogger;
import com.jenkinsmonitoring.logging.ParameterMasker;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.Secret;
import com.jenkinsmonitoring.support.TestServers;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The safety rules of the gateway, which hold before any network call is made. */
class JenkinsGatewayTest {

    private static final JenkinsServer SERVER = TestServers.catalog().servers().get(0);

    private static JenkinsGateway gateway(String... allowedTools) {
        JenkinsProperties properties = new JenkinsProperties(List.of(), List.of(allowedTools), "number,result", Duration.ofSeconds(2));
        McpCallLogger logger = new McpCallLogger(new ParameterMasker(new AppProperties.Masking("(?i).*(password|token|secret|key).*")));
        return new JenkinsGateway(properties, logger, new ToolCatalog());
    }

    @ParameterizedTest
    @ValueSource(strings = {"triggerBuild", "rebuildBuild", "replayBuild", "updateBuild"})
    void buildChangingToolsCanNeverBeCalledEvenWhenTheConfigurationAllowsThem(String tool) {
        JenkinsGateway gateway = gateway("getBuild", tool);

        assertThatThrownBy(() -> gateway.call(SERVER, tool, Map.of("jobFullName", "deploy")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changes builds");
    }

    @Test
    void aToolThatIsNotOnTheAllowlistIsRefused() {
        JenkinsGateway gateway = gateway("getBuild");

        assertThatThrownBy(() -> gateway.whoAmI(SERVER)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whoAmI").hasMessageContaining("not on the allowlist");
        assertThatThrownBy(() -> gateway.call(SERVER, "getBuildLog", Map.of("jobFullName", "deploy")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on the allowlist");
    }

    @Test
    void getBuildAndGetJobAreRefusedWithoutATree() {
        JenkinsGateway gateway = gateway("getBuild", "getJob");

        assertThatThrownBy(() -> gateway.call(SERVER, "getBuild", Map.of("jobFullName", "deploy")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be called with a tree");
        assertThatThrownBy(() -> gateway.call(SERVER, "getJob", Map.of("jobFullName", "deploy", "tree", "  ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be called with a tree");
    }

    @Test
    void aServerThatCannotBeReachedIsUnavailableAndTheCredentialsAreNotInTheMessage() {
        JenkinsGateway gateway = gateway("whoAmI");
        JenkinsServer nobodyListens = new JenkinsServer("http://127.0.0.1:1", "/mcp", "X", new Secret("Basic c2VjcmV0"));

        assertThatThrownBy(() -> gateway.whoAmI(nobodyListens))
                .isInstanceOfSatisfying(JenkinsException.Unavailable.class, e -> {
                    assertThat(e.getMessage()).contains("http://127.0.0.1:1").doesNotContain("c2VjcmV0");
                    assertThat(e.isAuthFailure()).isFalse();
                });
    }

    @Test
    void listingABuildChangingToolInTheAllowlistIsLoggedAsAnErrorAtStartup() {
        Logger logger = (Logger) LoggerFactory.getLogger(JenkinsGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            gateway("getBuild", "triggerBuild");

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("triggerBuild").contains("can never be called");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aFailedCallIsPassedToTheCallLoggerWithoutTheCredentials() {
        McpCallLogger callLogger = mock(McpCallLogger.class);
        JenkinsProperties properties = new JenkinsProperties(List.of(), List.of("whoAmI"), "number,result", Duration.ofSeconds(2));
        JenkinsGateway gateway = new JenkinsGateway(properties, callLogger, new ToolCatalog());
        JenkinsServer nobodyListens = new JenkinsServer("http://127.0.0.1:1", "/mcp", "X", new Secret("Basic c2VjcmV0"));

        assertThatThrownBy(() -> gateway.whoAmI(nobodyListens)).isInstanceOf(JenkinsException.Unavailable.class);

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(callLogger).outgoing(eq("http://127.0.0.1:1"), eq("whoAmI"), any(), response.capture());
        assertThat(response.getValue()).startsWith("FAILED").doesNotContain("c2VjcmV0");
    }
}
