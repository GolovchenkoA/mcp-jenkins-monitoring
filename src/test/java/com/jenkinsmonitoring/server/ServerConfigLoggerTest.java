package com.jenkinsmonitoring.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jenkinsmonitoring.config.JenkinsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The startup report of the configured Jenkins servers. */
class ServerConfigLoggerTest {

    @TempDir
    Path folder;

    private final Logger logger = (Logger) LoggerFactory.getLogger(ServerConfigLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ServerCatalog catalogOf(StandardEnvironment environment) {
        // With nothing configured under "jenkins" nothing is bound, exactly like the application at startup
        JenkinsProperties properties = Binder.get(environment).bind("jenkins", JenkinsProperties.class)
                .orElseGet(() -> new JenkinsProperties(Map.of(), List.of(), "", Duration.ofSeconds(10)));
        Map<String, String> variables = Map.of("SERVER1_AUTH", "Basic dGVzdDp0ZXN0");
        return new ServerCatalog(properties, variables::get);
    }

    private static ServerConfigLogger loggerFor(StandardEnvironment environment) {
        return new ServerConfigLogger(catalogOf(environment), environment);
    }

    /** A property source like the ones Spring Boot builds from environment variables. */
    private static PropertySource<?> variables(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource("test-systemEnvironment", variables);
    }

    private static void addFile(StandardEnvironment environment, Path file) throws IOException {
        new PropertiesPropertySourceLoader().load("file", new FileSystemResource(file))
                .forEach(environment.getPropertySources()::addFirst);
    }

    @Test
    void serversGivenByEnvironmentVariablesAreBoundAndReportedWithTheVariableName() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(variables(Map.of(
                "JENKINS_SERVER_0_URL", "https://jenkins-server1.com/mcp-server/mcp",
                "JENKINS_SERVER_0_AUTH", "SERVER1_AUTH")));

        loggerFor(environment).logServers();

        assertThat(messages().get(0)).isEqualTo("Jenkins servers in the configuration: 1 (1 in use, 0 not usable)");
        assertThat(messages().get(1)).contains("jenkins.server[0] = https://jenkins-server1.com")
                .contains("MCP endpoint /mcp-server/mcp").contains("protocol STREAMABLE")
                // the wording around the variable name depends on the property source; a real system environment
                // says: System Environment Property "JENKINS_SERVER_0_URL"
                .contains("\"JENKINS_SERVER_0_URL\"")
                .contains("credentials from environment variable SERVER1_AUTH");
    }

    @Test
    void aServerFromAFileSaysWhichFileLineAndColumnItCameFrom() throws IOException {
        Path file = Files.writeString(folder.resolve("application.properties"),
                "jenkins.server[0].url=https://jenkins-server1.com/mcp-server/mcp\njenkins.server[0].auth=SERVER1_AUTH\n");
        StandardEnvironment environment = new StandardEnvironment();
        addFile(environment, file);

        loggerFor(environment).logServers();

        assertThat(messages().get(1)).contains("jenkins.server[0] = https://jenkins-server1.com")
                .contains("application.properties").contains(" - 1:23");
    }

    @Test
    void serversFromAFileAndFromEnvironmentVariablesAreCombinedEvenWithAGapInTheNumbers() throws IOException {
        Path file = Files.writeString(folder.resolve("application.properties"),
                "jenkins.server[0].url=https://from-file.example.com/mcp-server/mcp\njenkins.server[0].auth=SERVER1_AUTH\n");
        StandardEnvironment environment = new StandardEnvironment();
        addFile(environment, file);
        environment.getPropertySources().addFirst(variables(Map.of(
                "JENKINS_SERVER_2_URL", "https://from-env.example.com/mcp-server/mcp",
                "JENKINS_SERVER_2_AUTH", "SERVER1_AUTH")));

        loggerFor(environment).logServers();

        assertThat(messages().get(0)).isEqualTo("Jenkins servers in the configuration: 2 (2 in use, 0 not usable)");
        assertThat(messages().get(1)).contains("jenkins.server[0] = https://from-file.example.com").contains("application.properties");
        assertThat(messages().get(2)).contains("jenkins.server[2] = https://from-env.example.com")
                .contains("JENKINS_SERVER_2_URL");
    }

    @Test
    void aServerThatIsNotUsableIsLoggedAsAnErrorWithTheReason() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("some-file", Map.of(
                "jenkins.server[0].url", "https://jenkins-server1.com/mcp-server/mcp",
                "jenkins.server[0].auth", "SERVER1_AUTH",
                "jenkins.server[1].url", "https://jenkins-server2.com/mcp-server/mcp",
                "jenkins.server[1].auth", "MISSING_VARIABLE")));

        loggerFor(environment).logServers();

        assertThat(messages().get(0)).isEqualTo("Jenkins servers in the configuration: 2 (1 in use, 1 not usable)");
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("jenkins.server[1] = https://jenkins-server2.com",
                        "NOT USED", "environment variable MISSING_VARIABLE is not set"));
    }

    @Test
    void credentialsPastedIntoTheConfigurationAreNeverLogged() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("some-file", Map.of(
                // a literal credential where the variable NAME belongs, and one inside a URL that cannot be understood
                "jenkins.server[0].url", "https://jenkins-server1.com/mcp-server/mcp",
                "jenkins.server[0].auth", "Basic c2VjcmV0LXRva2Vu",
                "jenkins.server[1].url", "ftp://someone:hunter2@jenkins-server2.com",
                "jenkins.server[1].auth", "SERVER1_AUTH")));

        loggerFor(environment).logServers();

        assertThat(messages()).noneMatch(message -> message.contains("c2VjcmV0LXRva2Vu") || message.contains("hunter2")
                || message.contains("dGVzdDp0ZXN0"));
        assertThat(messages()).anyMatch(message -> message.contains("jenkins.server[1] = <invalid url> is NOT USED"));
    }

    @Test
    void theCatalogKeepsEveryBlockAndTheReasonForEachRejectedOne() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("some-file", Map.of(
                "jenkins.server[0].url", "https://ok.example.com/mcp-server/mcp",
                "jenkins.server[0].auth", "SERVER1_AUTH",
                "jenkins.server[1].url", "https://ok.example.com/other/mcp",
                "jenkins.server[1].auth", "SERVER1_AUTH",
                "jenkins.server[2].url", "https://sse.example.com/mcp",
                "jenkins.server[2].protocol", "SSE",
                "jenkins.server[2].auth", "SERVER1_AUTH",
                "jenkins.server[3].url", "not a url",
                "jenkins.server[3].auth", "SERVER1_AUTH")));

        List<ServerEntry> entries = catalogOf(environment).entries();

        assertThat(entries).extracting(ServerEntry::index).containsExactly(0, 1, 2, 3);
        assertThat(entries.get(0).usable()).isTrue();
        assertThat(entries.get(0).authEnvVar()).isEqualTo("SERVER1_AUTH");
        assertThat(entries.get(1).problem()).contains("same canonical URL");
        assertThat(entries.get(2).problem()).contains("protocol must be STREAMABLE");
        assertThat(entries.get(3).problem()).contains("full http(s) URL");
        assertThat(entries.subList(1, 4)).allSatisfy(entry -> assertThat(entry.authEnvVar()).isNull());
    }

    @Test
    void whenNothingIsConfiguredTheReportSaysHowToAddAServer() {
        loggerFor(new StandardEnvironment()).logServers();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("jenkins.server[0].url", "JENKINS_SERVER_0_URL",
                    "JENKINS_SERVER_0_AUTH");
        });
    }

    @Test
    void theOldPluralNameInAFileIsIgnoredButExplained() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("some-file", Map.of(
                "jenkins.servers[0].url", "https://jenkins-server1.com/mcp-server/mcp",
                "jenkins.servers[0].auth", "SERVER1_AUTH")));

        loggerFor(environment).logServers();

        assertThat(catalogOf(environment).entries()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("No Jenkins server is configured"));
        assertThat(messages()).anyMatch(message -> message.contains("jenkins.servers[0]")
                && message.contains("renamed") && message.contains("jenkins.server[n]") && message.contains("ignored"));
    }

    @Test
    void noWarningAboutTheOldNameWhenItIsNotUsed() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(variables(Map.of(
                "JENKINS_SERVER_0_URL", "https://jenkins-server1.com/mcp-server/mcp",
                "JENKINS_SERVER_0_AUTH", "SERVER1_AUTH")));

        loggerFor(environment).logServers();

        assertThat(messages()).noneMatch(message -> message.contains("renamed"));
        assertThat(messages().get(1)).contains("jenkins.server[0] = https://jenkins-server1.com")
                .contains("\"JENKINS_SERVER_0_URL\"");
    }

    @Test
    void forTheSameNumberAnEnvironmentVariableWinsAndTheOtherSettingsComeFromTheFile() throws IOException {
        Path file = Files.writeString(folder.resolve("application.properties"),
                "jenkins.server[0].url=https://from-file.example.com/mcp-server/mcp\njenkins.server[0].auth=SERVER1_AUTH\n");
        StandardEnvironment environment = new StandardEnvironment();
        addFile(environment, file);
        environment.getPropertySources().addFirst(variables(Map.of(
                "JENKINS_SERVER_0_URL", "https://from-env.example.com/mcp-server/mcp")));

        loggerFor(environment).logServers();

        assertThat(messages().get(0)).isEqualTo("Jenkins servers in the configuration: 1 (1 in use, 0 not usable)");
        assertThat(messages().get(1)).contains("https://from-env.example.com").contains("\"JENKINS_SERVER_0_URL\"")
                .doesNotContain("from-file").contains("credentials from environment variable SERVER1_AUTH");
    }

    @Test
    void aSettingWithoutAServerNumberCannotBeBoundAndStopsTheStartup() {
        StandardEnvironment environment = new StandardEnvironment();
        // what JENKINS_SERVER_URL=... in the environment turns into
        environment.getPropertySources().addFirst(new MapPropertySource("some-file", Map.of(
                "jenkins.server.url", "https://jenkins-server1.com/mcp-server/mcp")));

        assertThatThrownBy(() -> Binder.get(environment).bind("jenkins", JenkinsProperties.class))
                .hasStackTraceContaining("Failed to bind properties under 'jenkins.server'")
                .hasStackTraceContaining("java.lang.Integer");
    }
}

