package com.jenkinsmonitoring.storage;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * One JSON file holding an array of records, like one database table. Reads are always ordered by
 * {@code created_at}, oldest first; records with the same time keep their insertion order. Every method is
 * synchronized, and callers that read and then write hold the table's monitor for the whole change.
 */
final class JsonTable<T> {

    private static final int REPLACE_ATTEMPTS = 5;
    private static final long REPLACE_RETRY_MILLIS = 50;

    private final Path file;
    private final JavaType listType;
    private final Function<T, LocalDateTime> createdAt;

    JsonTable(Path file, Class<T> type, Function<T, LocalDateTime> createdAt) {
        this.file = file;
        this.listType = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, type);
        this.createdAt = createdAt;
    }

    Path file() {
        return file;
    }

    synchronized List<T> readAll() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            if (Files.size(file) == 0) {
                return new ArrayList<>();
            }
            List<T> items = new ArrayList<>(Json.MAPPER.<List<T>>readValue(file.toFile(), listType));
            // The list sort is stable, so equal times keep the order they have in the file
            items.sort(Comparator.comparing(createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return items;
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    synchronized void writeAll(List<T> items) {
        writeAtomically(file, items);
    }

    /** The records as JSON nodes, for callers that want to skip a bad entry instead of failing on the file. */
    synchronized List<JsonNode> readNodes() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            if (Files.size(file) == 0) {
                return new ArrayList<>();
            }
            JsonNode root = Json.MAPPER.readTree(file.toFile());
            if (!root.isArray()) {
                throw new IOException("the file must contain a JSON array");
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes to a temporary file, forces it to disk, then replaces the target, so neither a crash nor a power
     * loss leaves a half-written file. Windows refuses to replace a file that a virus scanner or editor has
     * open for a moment, so the replacement is retried briefly.
     */
    static void writeAtomically(Path file, Object value) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            ByteBuffer bytes = ByteBuffer.wrap(Json.MAPPER.writeValueAsBytes(value));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            replace(temp, file);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp); // do not leave a stale temporary file behind
            } catch (IOException ignored) {
                // the write error below is the one that matters
            }
            throw new StorageException("Cannot write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void replace(Path temp, Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                // Windows reports a target that a scanner or editor holds open as access denied or as a
                // sharing violation, depending on the case; both are usually gone a moment later
                if (attempt >= REPLACE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(REPLACE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
