package com.jenkinsmonitoring.server;

/** Holds a credential and refuses to print it, so it cannot leak through a log line or a record's toString. */
public record Secret(String value) {

    @Override
    public String toString() {
        return "***";
    }
}
