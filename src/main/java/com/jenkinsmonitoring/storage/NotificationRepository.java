package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One file per rule: {@code notification_{ruleId}.json}. */
@Component
public class NotificationRepository {

    private static final Pattern FILE_NAME = Pattern.compile("notification_(\\d+)\\.json");

    private final Path directory;
    // One table object per file, so all writers of a file share the same monitor
    private final ConcurrentMap<Integer, JsonTable<NotificationRecord>> tables = new ConcurrentHashMap<>();

    public NotificationRepository(AppProperties.Files files) {
        this.directory = files.storagePath();
    }

    /** Inserts unless the rule already has a notification for that build URL. */
    public boolean insertIfAbsent(NotificationRecord record) {
        JsonTable<NotificationRecord> table = table(record.ruleId());
        synchronized (table) {
            List<NotificationRecord> all = table.readAll();
            if (all.stream().anyMatch(existing -> existing.url().equals(record.url()))) {
                return false;
            }
            all.add(record);
            table.writeAll(all);
            return true;
        }
    }

    /** Notifications of all rules, oldest first. A null status means every status. */
    public List<NotificationRecord> find(NotificationStatus status) {
        List<NotificationRecord> result = new ArrayList<>();
        for (int ruleId : ruleIds()) {
            for (NotificationRecord record : table(ruleId).readAll()) {
                if (status == null || record.status() == status) {
                    result.add(record);
                }
            }
        }
        result.sort(Comparator.comparing(NotificationRecord::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(NotificationRecord::ruleId));
        return result;
    }

    public int deleteOlderThan(LocalDateTime cutoff) {
        int deleted = 0;
        for (int ruleId : ruleIds()) {
            JsonTable<NotificationRecord> table = table(ruleId);
            synchronized (table) {
                List<NotificationRecord> all = table.readAll();
                int before = all.size();
                all.removeIf(record -> record.createdAt() != null && record.createdAt().isBefore(cutoff));
                if (all.size() != before) {
                    table.writeAll(all);
                    deleted += before - all.size();
                }
            }
        }
        return deleted;
    }

    public void verify() {
        ruleIds().forEach(id -> table(id).readAll());
    }

    private JsonTable<NotificationRecord> table(int ruleId) {
        return tables.computeIfAbsent(ruleId, id -> new JsonTable<>(
                directory.resolve("notification_" + id + ".json"), NotificationRecord.class, NotificationRecord::createdAt));
    }

    private List<Integer> ruleIds() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> FILE_NAME.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Cannot list " + directory + ": " + e.getMessage(), e);
        }
    }
}
