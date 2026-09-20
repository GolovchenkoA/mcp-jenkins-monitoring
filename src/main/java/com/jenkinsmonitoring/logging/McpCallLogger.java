package com.jenkinsmonitoring.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Logs every MCP tool call with its parameters and the full response body. The logger {@code mcp.calls} is
 * at DEBUG only in the debug profile, so nothing is logged otherwise. Credentials are never part of a call,
 * and parameter values that look like secrets are masked.
 */
@Component
public class McpCallLogger {

    private static final Logger log = LoggerFactory.getLogger("mcp.calls");

    private final ParameterMasker masker;

    public McpCallLogger(ParameterMasker masker) {
        this.masker = masker;
    }

    /** A call from this application to a Jenkins MCP server. */
    public void outgoing(String server, String tool, Map<String, Object> arguments, String response) {
        if (log.isDebugEnabled()) {
            log.debug("OUT {} {} arguments={} response={}", server, tool, masker.maskObject(arguments),
                    masker.maskJson(response));
        }
    }

    /** A call from a user's client to a tool of this application. */
    public void incoming(String tool, Map<String, Object> arguments, String response) {
        if (log.isDebugEnabled()) {
            log.debug("IN {} arguments={} response={}", tool, masker.maskObject(arguments), masker.maskJson(response));
        }
    }
}
