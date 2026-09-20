package com.jenkinsmonitoring.tools;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.logging.McpCallLogger;
import com.jenkinsmonitoring.logging.ParameterMasker;
import com.jenkinsmonitoring.status.StatusService;
import com.jenkinsmonitoring.storage.Json;
import com.jenkinsmonitoring.storage.JobRecord;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRecord;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.NotificationStatus;
import com.jenkinsmonitoring.support.TestServers;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitoringToolsTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 14, 0);

    @TempDir
    Path folder;

    private JobRepository jobs;
    private NotificationRepository notifications;
    private StatusService statusService;
    private MonitoringTools tools;

    @BeforeEach
    void setUp() {
        var files = TestServers.files(folder);
        jobs = new JobRepository(files);
        notifications = new NotificationRepository(files);
        statusService = mock(StatusService.class);
        var executor = new ToolExecutor(new McpCallLogger(new ParameterMasker(new AppProperties.Masking(".*secret.*"))));
        // default limit 3, at most 2 notifications
        tools = new MonitoringTools(jobs, notifications, statusService,
                new AppProperties.Tools(new AppProperties.Tools.RecentJobs(3), new AppProperties.Tools.Notifications(2)), executor);
    }

    private static JsonNode json(CallToolResult result) {
        return Json.MAPPER.readTree(text(result));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private void jobs(int count) {
        for (int i = 1; i <= count; i++) {
            jobs.insertIfAbsent(new JobRecord(T0.plusMinutes(i), "https://j/" + i + "/", "s", "j", i, "SUCCESS",
                    null, null, null, null, null, Map.of(), List.of()));
        }
    }

    private void notifications(int count) {
        for (int i = 1; i <= count; i++) {
            notifications.insertIfAbsent(new NotificationRecord(T0.plusMinutes(i), 1, "https://j/" + i + "/",
                    i % 2 == 0 ? NotificationStatus.DELIVERED : NotificationStatus.PENDING, "s", "j", i, "SUCCESS"));
        }
    }

    @Test
    void recentJobsUsesTheConfiguredDefaultLimitAndReturnsOldestFirst() {
        jobs(5);

        JsonNode result = json(tools.getRecentJobs(null));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).path("build_number").asInt()).isEqualTo(3);
        assertThat(result.get(2).path("build_number").asInt()).isEqualTo(5);
    }

    @Test
    void recentJobsHonoursAnExplicitLimit() {
        jobs(5);

        assertThat(json(tools.getRecentJobs(1))).hasSize(1);
        assertThat(json(tools.getRecentJobs(50))).hasSize(5);
    }

    @Test
    void recentJobsRejectsALimitThatIsNotPositive() {
        CallToolResult zero = tools.getRecentJobs(0);
        CallToolResult negative = tools.getRecentJobs(-5);

        assertThat(zero.isError()).isTrue();
        assertThat(text(zero)).contains("positive");
        assertThat(negative.isError()).isTrue();
    }

    @Test
    void notificationsAreCappedWithANoteAboutHowManyThereAre() {
        notifications(4);

        JsonNode result = json(tools.getNotifications(null));

        assertThat(result.path("total").asInt()).isEqualTo(4);
        assertThat(result.path("shown").asInt()).isEqualTo(2);
        assertThat(result.path("note").asText()).isEqualTo("Showing the newest 2 of 4 notifications.");
        assertThat(result.path("notifications")).hasSize(2);
        assertThat(result.path("notifications").get(1).path("build_number").asInt()).isEqualTo(4);
    }

    @Test
    void notificationsWithinTheCapHaveNoNote() {
        notifications(2);

        JsonNode result = json(tools.getNotifications(null));

        assertThat(result.path("total").asInt()).isEqualTo(2);
        assertThat(result.has("note")).isFalse();
    }

    @Test
    void notificationsCanBeFilteredByStatusIgnoringCase() {
        notifications(4);

        assertThat(json(tools.getNotifications("pending")).path("total").asInt()).isEqualTo(2);
        assertThat(json(tools.getNotifications(" DELIVERED ")).path("total").asInt()).isEqualTo(2);
        assertThat(json(tools.getNotifications("")).path("total").asInt()).isEqualTo(4);
        assertThat(json(tools.getNotifications("FAILED")).path("total").asInt()).isZero();
    }

    @Test
    void anUnknownNotificationStatusIsAToolErrorListingTheValidOnes() {
        CallToolResult result = tools.getNotifications("bogus");

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("PENDING", "DELIVERED", "FAILED");
    }

    @Test
    void statusReturnsTheReportOfTheStatusService() {
        when(statusService.report()).thenReturn(new StatusService.Report(StatusService.Level.PROBLEMS,
                List.of(new StatusService.Check("collector", StatusService.Level.PROBLEMS, "stalled"))));

        JsonNode result = json(tools.status());

        assertThat(result.path("status").asText()).isEqualTo("PROBLEMS");
        assertThat(result.path("checks").get(0).path("name").asText()).isEqualTo("collector");
    }

    @Test
    void anUnexpectedFailureBecomesAnInternalErrorNotAnException() {
        when(statusService.report()).thenThrow(new IllegalStateException("boom"));

        CallToolResult result = tools.status();

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Internal error").contains("boom");
    }
}
