package com.jenkinsmonitoring.storage;

import java.time.format.DateTimeFormatter;

public final class Formats {

    /** User-friendly local date time with milliseconds, used by every stored {@code created_at}. */
    public static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss.SSS";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME);

    private Formats() {
    }
}
