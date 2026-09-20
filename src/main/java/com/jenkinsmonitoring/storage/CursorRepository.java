package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The collector's position per (server, job); stored in {@code latest_jobs.json}. */
@Component
public class CursorRepository {

    private final JsonTable<Cursor> table;

    public CursorRepository(AppProperties.Files files) {
        this.table = new JsonTable<>(files.storagePath().resolve("latest_jobs.json"), Cursor.class, Cursor::createdAt);
    }

    public Optional<Cursor> find(String server, String job) {
        return table.readAll().stream().filter(cursor -> cursor.is(server, job)).findFirst();
    }

    public void save(Cursor cursor) {
        synchronized (table) {
            List<Cursor> all = table.readAll();
            all.removeIf(existing -> existing.is(cursor.server(), cursor.job()));
            all.add(cursor);
            table.writeAll(all);
        }
    }

    public void delete(String server, String job) {
        synchronized (table) {
            List<Cursor> all = table.readAll();
            if (all.removeIf(cursor -> cursor.is(server, job))) {
                table.writeAll(all);
            }
        }
    }

    public void verify() {
        table.readAll();
    }
}
