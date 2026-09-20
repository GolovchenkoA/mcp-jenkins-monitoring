package com.jenkinsmonitoring.server;

/** A configured server that cannot be used. The application keeps running so {@code status} can report it. */
public record ServerProblem(String server, String message) {
}
