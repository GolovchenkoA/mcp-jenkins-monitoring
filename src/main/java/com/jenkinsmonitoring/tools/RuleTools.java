package com.jenkinsmonitoring.tools;

import com.jenkinsmonitoring.rules.RuleService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tools to manage the rules. {@code server} and {@code job} are not marked required in the schema on
 * purpose: a request that lacks them (for example "notify me about user X") should reach us and get an
 * explanation with an example, not be rejected by the schema check.
 */
@Component
public class RuleTools {

    private final RuleService rules;
    private final ToolExecutor executor;

    RuleTools(RuleService rules, ToolExecutor executor) {
        this.rules = rules;
        this.executor = executor;
    }

    @McpTool(name = "addRule",
            description = "Start watching a Jenkins job. REQUIRED: server and job; a rule cannot be made from a user "
                    + "alone. The server and the job are checked on Jenkins first. Only one rule per server and job "
                    + "exists; to change it use removeRule and then addRule.",
            annotations = @McpTool.McpAnnotations(title = "Add a rule", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = true))
    public CallToolResult addRule(
            @McpToolParam(required = false, description = "REQUIRED. The Jenkins server as a URL or host name, for example "
                    + "https://jenkins-server1.com or jenkins-server1.com") String server,
            @McpToolParam(required = false, description = "REQUIRED. The exact full name of the Jenkins job, "
                    + "including folders, for example folder/my-job") String job,
            @McpToolParam(required = false, description = "Optional conditions a build must meet, as an object. Keys: "
                    + "user (name.surname of who started the build), status (SUCCESS, FAILURE, UNSTABLE or ABORTED), "
                    + "playbook, or the name of any build parameter. A value is a string or a list of strings (any of). "
                    + "Example: {\"user\":\"artem.holovchenko\",\"status\":\"FAILURE\",\"playbook\":[\"a\",\"b\"]}")
            Map<String, Object> conditions) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("server", server);
        arguments.put("job", job);
        arguments.put("conditions", conditions);
        return executor.run("addRule", arguments, () -> rules.add(server, job, conditions));
    }

    @McpTool(name = "removeRule",
            description = "Stop watching a Jenkins job. Both server and job are required. Stored build history "
                    + "is kept until it expires.",
            annotations = @McpTool.McpAnnotations(title = "Remove a rule", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    public CallToolResult removeRule(
            @McpToolParam(required = false, description = "REQUIRED. The Jenkins server as a URL or host name") String server,
            @McpToolParam(required = false, description = "REQUIRED. The exact full name of the Jenkins job") String job) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("server", server);
        arguments.put("job", job);
        return executor.run("removeRule", arguments, () -> rules.remove(server, job));
    }

    @McpTool(name = "listRules",
            description = "List all rules: id, server, job and conditions.",
            annotations = @McpTool.McpAnnotations(title = "List rules", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listRules() {
        return executor.run("listRules", Map.of(), rules::list);
    }
}
