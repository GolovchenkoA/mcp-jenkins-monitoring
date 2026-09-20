package com.jenkinsmonitoring.tools;

import com.jenkinsmonitoring.storage.Json;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

final class ToolResults {

    private ToolResults() {
    }

    static CallToolResult ok(Object value) {
        return CallToolResult.builder().addTextContent(Json.MAPPER.writeValueAsString(value)).isError(false).build();
    }

    static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
