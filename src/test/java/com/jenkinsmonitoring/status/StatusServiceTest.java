package com.jenkinsmonitoring.status;

import com.jenkinsmonitoring.collector.Collector;
import com.jenkinsmonitoring.collector.CollectorFixture;
import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.jenkins.ToolCatalog;
import com.jenkinsmonitoring.rules.RuleService;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.status.StatusService.Check;
import com.jenkinsmonitoring.status.StatusService.Level;
import com.jenkinsmonitoring.status.StatusService.Report;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.support.FakeJenkins;
import com.jenkinsmonitoring.support.MutableClock;
import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class StatusServiceTest {

    private static final String SERVER_A = "https://localhost:8443";
    private static final String SERVER_B = "https://localhost:8444";

    @TempDir
    Path folder;

    private FakeJenkins jenkins;
    private MutableClock clock;
    private ToolCatalog toolCatalog;
    private ServerCatalog servers;
    private RuleService rules;
    private Collector collector;
    private StatusService status;

    @BeforeEach
    void twoHealthyServers() {
        start(TestServers.properties(TestServers.server(SERVER_A + "/mcp"), TestServers.server(SERVER_B + "/mcp")));
    }

    private void start(JenkinsProperties properties) {
        start(properties, TestServers.environment());
    }

    private void start(JenkinsProperties properties, Function<String, String> environment) {
        var files = TestServers.files(folder);
        jenkins = new FakeJenkins();
        clock = new MutableClock();
        toolCatalog = new ToolCatalog();
        servers = new ServerCatalog(properties, environment);
        var cursors = new CursorRepository(files);
        var ruleRepository = new RuleRepository(files);
        var jobs = new JobRepository(files);
        var notifications = new NotificationRepository(files);
        rules = new RuleService(ruleRepository, servers, jenkins, cursors, clock);
        rules.load();
        collector = CollectorFixture.collector(rules, servers, jenkins, cursors, jobs, notifications, clock);
        for (JenkinsServer server : servers.servers()) {
            toolCatalog.update(server.canonical(), properties.allowedTools().stream()
                    .map(name -> new ToolCatalog.ToolInfo(name, "", true)).toList());
        }
        status = new StatusService(servers, jenkins, toolCatalog, properties, rules, collector, ruleRepository, jobs,
                notifications, cursors, files, new AppProperties.Scheduling(new AppProperties.Scheduling.JobCheck("0 */5 * * * *")),
                clock);
    }

    private static Check check(Report report, String namePrefix) {
        return report.checks().stream().filter(c -> c.name().startsWith(namePrefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + namePrefix + " in " + report.checks()));
    }

    private static List<Check> checks(Report report, String namePrefix) {
        return report.checks().stream().filter(c -> c.name().startsWith(namePrefix)).toList();
    }

    @Test
    void everythingIsOkWhenAllServersAnswer() {
        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.OK);
        assertThat(report.checks()).allMatch(c -> c.status() == Level.OK);
        assertThat(check(report, "jenkins servers available").message()).contains("All 2");
    }

    @Test
    void noServerConfiguredIsCritical() {
        start(TestServers.properties());

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "servers configured").message()).contains("jenkins.server[");
    }

    @Test
    void aServerWithAConfigurationProblemIsCritical() {
        start(TestServers.properties(new JenkinsProperties.Server(SERVER_A + "/mcp", "STREAMABLE", "MISSING_VARIABLE")),
                name -> null);

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "server " + SERVER_A).message()).contains("MISSING_VARIABLE");
    }

    @Test
    void aHostThatDoesNotResolveIsCriticalAndMentionsTheVpn() {
        start(TestServers.properties(TestServers.server("https://no-such-host.invalid/mcp")));

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "dns https://no-such-host.invalid").status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "dns https://no-such-host.invalid").message()).contains("VPN");
    }

    @Test
    void someServersDownMeansServersAvailablePartially() {
        jenkins.downServers.add(SERVER_B);

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.PROBLEMS);
        assertThat(check(report, "jenkins servers available").status()).isEqualTo(Level.PROBLEMS);
        assertThat(check(report, "jenkins servers available").message()).contains("partially");
        assertThat(check(report, "jenkins " + SERVER_B).status()).isEqualTo(Level.PROBLEMS);
        assertThat(check(report, "jenkins " + SERVER_A).status()).isEqualTo(Level.OK);
    }

    @Test
    void noServerAnsweringIsCritical() {
        jenkins.unavailable = true;

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "jenkins servers available").message()).contains("No Jenkins server answers");
    }

    @Test
    void rejectedCredentialsAreNamedAsSuch() {
        jenkins.downServers.add(SERVER_A);
        jenkins.authFailure = true;

        Report report = status.report();

        assertThat(check(report, "jenkins " + SERVER_A).message()).contains("rejected the configured credentials");
    }

    @Test
    void jenkinsInQuietModeIsAProblem() {
        jenkins.jenkinsStatus = JsonMapper.builder().build().readTree("{\"Quiet Mode\": true, \"Active administrative monitors\": [], \"Root URL Status\": \"OK\"}");

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.PROBLEMS);
        assertThat(checks(report, "jenkins health").get(0).message()).contains("quiet mode");
    }

    @Test
    void administrativeMonitorsAndABadRootUrlAreProblems() {
        JsonNode node = JsonMapper.builder().build().readTree("{\"Quiet Mode\": false, \"Active administrative monitors\": [\"x\", \"y\"], \"Root URL Status\": \"Misconfigured\"}");
        jenkins.jenkinsStatus = node;

        Check health = checks(status.report(), "jenkins health").get(0);

        assertThat(health.status()).isEqualTo(Level.PROBLEMS);
        assertThat(health.message()).contains("2 active administrative monitor(s)").contains("Root URL status is Misconfigured");
    }

    @Test
    void aServerThatDoesNotOfferAnAllowlistedToolIsAProblem() {
        toolCatalog.update(SERVER_A, List.of(new ToolCatalog.ToolInfo("getBuild", "", true)));

        Check tools = check(status.report(), "jenkins tools " + SERVER_A);

        assertThat(tools.status()).isEqualTo(Level.PROBLEMS);
        assertThat(tools.message()).contains("does not offer").contains("getJob");
    }

    @Test
    void unreadableStorageIsCritical() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("jobs.json"), "{ not json");

        Report report = status.report();

        assertThat(report.status()).isEqualTo(Level.CRITICAL);
        assertThat(check(report, "storage").message()).contains("jobs.json");
    }

    @Test
    void aCollectorThatStoppedRunningIsAProblemUntilItRunsAgain() {
        clock.advance(Duration.ofMinutes(9));
        assertThat(check(status.report(), "collector").status()).isEqualTo(Level.OK);

        clock.advance(Duration.ofMinutes(2));
        assertThat(check(status.report(), "collector").status()).isEqualTo(Level.PROBLEMS);
        assertThat(check(status.report(), "collector").message()).contains("has not finished a run");

        collector.runOnce();
        assertThat(check(status.report(), "collector").status()).isEqualTo(Level.OK);
    }

    @Test
    void skippedRulesAreListedAsProblems() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("rules.json"), """
                [{"id": 2, "created_at": "2026-09-20 14:05:33.123", "server": "unknown.example.com", "job": "x"}]""");
        rules.load();

        Check ruleCheck = check(status.report(), "rules");

        assertThat(ruleCheck.status()).isEqualTo(Level.PROBLEMS);
        assertThat(ruleCheck.message()).contains("Rule 2");
    }

    @Test
    void collectorProblemsAreListedPerRule() {
        jenkins.add(1, "SUCCESS");
        rules.add(SERVER_A, "deploy", null);
        collector.runOnce();
        jenkins.jobMissing = true;
        collector.runOnce();

        Check ruleCheck = check(status.report(), "rules");

        assertThat(ruleCheck.status()).isEqualTo(Level.PROBLEMS);
        assertThat(ruleCheck.message()).contains("Rule 1").contains("was not found");
    }
}
