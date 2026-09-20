package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class JobRepository {

    private final JsonTable<JobRecord> table;

    public JobRepository(AppProperties.Files files) {
        this.table = new JsonTable<>(files.storagePath().resolve("jobs.json"), JobRecord.class, JobRecord::createdAt);
    }

    /** Inserts the record unless one with the same build URL exists. Returns whether it was inserted. */
    public boolean insertIfAbsent(JobRecord record) {
        synchronized (table) {
            List<JobRecord> all = table.readAll();
            if (all.stream().anyMatch(existing -> existing.url().equals(record.url()))) {
                return false;
            }
            all.add(record);
            table.writeAll(all);
            return true;
        }
    }

    public List<JobRecord> findAll() {
        return table.readAll();
    }

    /** The newest {@code limit} records, oldest of them first. */
    public List<JobRecord> recent(int limit) {
        List<JobRecord> all = table.readAll();
        int from = Math.max(0, all.size() - Math.max(limit, 0));
        return List.copyOf(all.subList(from, all.size()));
    }

    public int deleteOlderThan(LocalDateTime cutoff) {
        synchronized (table) {
            List<JobRecord> all = table.readAll();
            int before = all.size();
            all.removeIf(record -> record.createdAt() != null && record.createdAt().isBefore(cutoff));
            if (all.size() != before) {
                table.writeAll(all);
            }
            return before - all.size();
        }
    }

    public void verify() {
        table.readAll();
    }
}
