package com.jenkinsmonitoring.tools;

import com.jenkinsmonitoring.common.UserInputException;
import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.status.StatusService;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRecord;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.NotificationStatus;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only tools over the stored history, notifications and the server status. */
@Component
public class MonitoringTools {

    private record NotificationList(int total, int shown, String note, List<NotificationRecord> notifications) {
    }

    private final JobRepository jobs;
    private final NotificationRepository notifications;
    private final StatusService status;
    private final AppProperties.Tools properties;
    private final ToolExecutor executor;

    MonitoringTools(JobRepository jobs, NotificationRepository notifications, StatusService status,
                    AppProperties.Tools properties, ToolExecutor executor) {
        this.jobs = jobs;
        this.notifications = notifications;
        this.status = status;
        this.properties = properties;
        this.executor = executor;
    }

    @McpTool(name = "getRecentJobs",
            description = "The most recent Jenkins builds that matched a rule, oldest of them first.",
            annotations = @McpTool.McpAnnotations(title = "Recent jobs", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult getRecentJobs(
            @McpToolParam(required = false, description = "How many records to return; the default is configured, usually 20")
            Integer limit) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("limit", limit);
        return executor.run("getRecentJobs", arguments, () -> {
            int count = limit == null ? properties.recentJobs().defaultLimit() : limit;
            if (count <= 0) {
                throw new UserInputException("limit must be a positive number.");
            }
            return jobs.recent(count);
        });
    }

    @McpTool(name = "getNotifications",
            description = "Notifications with their delivery status (PENDING, DELIVERED or FAILED). Without a status "
                    + "all notifications are returned.",
            annotations = @McpTool.McpAnnotations(title = "Notifications", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult getNotifications(
            @McpToolParam(required = false, description = "PENDING, DELIVERED or FAILED. Omit to get all.") String status) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("status", status);
        return executor.run("getNotifications", arguments, () -> listNotifications(status));
    }

    @McpTool(name = "status",
            description = "The health of this server: OK, PROBLEMS or CRITICAL, with the list of checks behind it "
                    + "(Jenkins host names, Jenkins reachability, storage, rules, scheduler).",
            annotations = @McpTool.McpAnnotations(title = "Server status", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public CallToolResult status() {
        return executor.run("status", Map.of(), status::report);
    }

    private NotificationList listNotifications(String statusText) {
        NotificationStatus wanted = null;
        if (statusText != null && !statusText.isBlank()) {
            wanted = NotificationStatus.parse(statusText).orElseThrow(() ->
                    new UserInputException("Unknown status '" + statusText + "'. Use PENDING, DELIVERED or FAILED."));
        }
        List<NotificationRecord> all = notifications.find(wanted);
        int max = properties.notifications().maxResults();
        if (all.size() <= max) {
            return new NotificationList(all.size(), all.size(), null, all);
        }
        return new NotificationList(all.size(), max, "Showing the newest " + max + " of " + all.size() + " notifications.",
                all.subList(all.size() - max, all.size()));
    }
}
