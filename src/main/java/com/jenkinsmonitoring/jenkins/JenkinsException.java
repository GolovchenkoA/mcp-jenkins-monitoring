package com.jenkinsmonitoring.jenkins;

/** What can go wrong when calling a Jenkins MCP server. The subclasses tell the callers what to do next. */
public abstract class JenkinsException extends RuntimeException {

    protected JenkinsException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The call worked but there is nothing: the job or build does not exist, or it is not visible to us. */
    public static final class NotFound extends JenkinsException {
        public NotFound(String message) {
            super(message, null);
        }
    }

    /** The server could not be reached or refused the credentials. */
    public static final class Unavailable extends JenkinsException {
        private final boolean authFailure;

        public Unavailable(String message, boolean authFailure, Throwable cause) {
            super(message, cause);
            this.authFailure = authFailure;
        }

        public boolean isAuthFailure() {
            return authFailure;
        }
    }

    /** The server answered but reported an error for the tool call. */
    public static final class ToolError extends JenkinsException {
        public ToolError(String message) {
            super(message, null);
        }
    }
}
