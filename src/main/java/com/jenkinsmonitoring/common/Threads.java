package com.jenkinsmonitoring.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon threads for short background work (connecting, closing, DNS lookups). They are plain platform
 * threads rather than virtual threads so the application also runs on Java 17; a daemon thread never keeps
 * the JVM from exiting.
 */
public final class Threads {

    private Threads() {
    }

    public static Thread startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
