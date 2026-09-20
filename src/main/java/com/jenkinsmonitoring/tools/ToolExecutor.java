package com.jenkinsmonitoring.tools;

import com.jenkinsmonitoring.common.UserInputException;
import com.jenkinsmonitoring.logging.McpCallLogger;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs the body of every tool in one place, so all tools report errors the same way and every call is
 * logged in the debug profile. A {@link UserInputException} becomes a tool error with its own message;
 * anything else is a bug or an unexpected failure and is reported as an internal error.
 */
@Component
class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final McpCallLogger callLogger;

    ToolExecutor(McpCallLogger callLogger) {
        this.callLogger = callLogger;
    }

    CallToolResult run(String tool, Map<String, Object> arguments, Supplier<Object> body) {
        CallToolResult result;
        try {
            result = ToolResults.ok(body.get());
        } catch (UserInputException e) {
            result = ToolResults.error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tool {} failed", tool, e);
            result = ToolResults.error("Internal error in " + tool + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        callLogger.incoming(tool, arguments, textOf(result));
        return result;
    }

    private static String textOf(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .findFirst()
                .orElse("");
    }
}
