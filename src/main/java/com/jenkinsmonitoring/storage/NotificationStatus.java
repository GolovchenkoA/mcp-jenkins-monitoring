package com.jenkinsmonitoring.storage;

import java.util.Arrays;
import java.util.Optional;

public enum NotificationStatus {
    PENDING, DELIVERED, FAILED;

    public static Optional<NotificationStatus> parse(String text) {
        return Arrays.stream(values()).filter(status -> status.name().equalsIgnoreCase(text.trim())).findFirst();
    }
}
