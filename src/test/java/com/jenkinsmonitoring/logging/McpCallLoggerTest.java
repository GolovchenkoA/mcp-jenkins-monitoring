package com.jenkinsmonitoring.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jenkinsmonitoring.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpCallLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("mcp.calls");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final McpCallLogger callLogger = new McpCallLogger(
            new ParameterMasker(new AppProperties.Masking("(?i).*(password|token|secret|key).*")));
    private Level originalLevel;

    @BeforeEach
    void capture() {
        originalLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void inTheDebugProfileACallIsLoggedWithItsParametersAndTheFullBody() {
        logger.setLevel(Level.DEBUG);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("jobFullName", "deploy");
        arguments.put("buildNumber", 7);
        String body = "{\"status\":\"COMPLETED\",\"result\":{\"number\":7,\"description\":\"" + "x".repeat(2000) + "\"}}";

        callLogger.outgoing("https://jenkins.example.com", "getBuild", arguments, body);

        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message).startsWith("OUT https://jenkins.example.com getBuild");
            assertThat(message).contains("\"jobFullName\":\"deploy\"", "\"buildNumber\":7");
            assertThat(message).contains("x".repeat(2000)); // not truncated
        });
    }

    @Test
    void callsToOurOwnToolsAreLoggedToo() {
        logger.setLevel(Level.DEBUG);

        callLogger.incoming("addRule", Map.of("job", "deploy"), "{\"id\":1}");

        assertThat(messages()).singleElement().satisfies(message ->
                assertThat(message).startsWith("IN addRule").contains("\"job\":\"deploy\"").contains("{\"id\":1}"));
    }

    @Test
    void secretParametersAndKeysAreMaskedEvenInTheDebugLog() {
        logger.setLevel(Level.DEBUG);
        String body = "{\"result\":{\"actions\":[{\"parameters\":[{\"name\":\"DB_PASSWORD\",\"value\":\"hunter2\"},"
                + "{\"name\":\"REGION\",\"value\":\"eastus\"}]}]}}";

        callLogger.outgoing("s", "getBuild", Map.of("apiToken", "abc123"), body);

        assertThat(messages().get(0)).doesNotContain("hunter2", "abc123").contains("eastus", "***");
    }

    @Test
    void nothingIsLoggedOutsideTheDebugProfile() {
        logger.setLevel(Level.INFO);

        callLogger.outgoing("s", "getBuild", Map.of(), "{}");
        callLogger.incoming("status", Map.of(), "{}");

        assertThat(messages()).isEmpty();
    }
}
