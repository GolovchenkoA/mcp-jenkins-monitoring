package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.config.AppProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class RuleRepository {

    private final JsonTable<Rule> table;
    private final Path sequenceFile;

    public RuleRepository(AppProperties.Files files) {
        this.table = new JsonTable<>(files.storagePath().resolve("rules.json"), Rule.class, Rule::createdAt);
        this.sequenceFile = files.storagePath().resolve("sequences.json");
    }

    /**
     * The rules of the file, the entries that could not be read at all (kept as they are), and a message for
     * each of those.
     */
    public record Loaded(List<Rule> rules, List<JsonNode> unreadable, List<String> problems) {
    }

    /**
     * Reads the file entry by entry, so one malformed rule does not hide the others. A file that is not a
     * JSON array at all is an error.
     */
    public Loaded load() {
        List<Rule> rules = new ArrayList<>();
        List<JsonNode> unreadable = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<JsonNode> nodes = table.readNodes();
        for (int i = 0; i < nodes.size(); i++) {
            try {
                rules.add(Json.MAPPER.treeToValue(nodes.get(i), Rule.class));
            } catch (RuntimeException e) {
                unreadable.add(nodes.get(i));
                problems.add("Entry " + (i + 1) + " of " + table.file().getFileName() + " was skipped: " + e.getMessage());
            }
        }
        rules.sort(Comparator.comparing(Rule::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Loaded(rules, unreadable, problems);
    }

    /**
     * Writes the rules in use, followed by the entries the application could not use. Those are written back
     * untouched: a rule for a server that is missing from the configuration for a moment must not be lost
     * because someone else added a rule.
     */
    public void saveAll(List<Rule> rules, List<Rule> unusable, List<JsonNode> unreadable) {
        List<JsonNode> nodes = new ArrayList<>();
        rules.forEach(rule -> nodes.add(Json.MAPPER.valueToTree(rule)));
        unusable.forEach(rule -> nodes.add(Json.MAPPER.valueToTree(rule)));
        nodes.addAll(unreadable);
        JsonTable.writeAtomically(table.file(), nodes);
    }

    /** Changes when the file is edited, so a hand edit made while running is noticed before we overwrite it. */
    public long lastModified() {
        try {
            return Files.exists(table.file()) ? Files.getLastModifiedTime(table.file()).toMillis() : 0;
        } catch (IOException e) {
            throw new StorageException("Cannot check " + table.file().getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The next rule id. The last id handed out is kept in its own file so an id is never reused after a rule
     * is removed; the old notification file of a removed rule must not attach to a new rule.
     */
    public synchronized int nextId(List<Rule> current) {
        int last = readLastId();
        for (Rule rule : current) {
            last = Math.max(last, rule.id());
        }
        int next = last + 1;
        JsonTable.writeAtomically(sequenceFile, Map.of("rules", next));
        return next;
    }

    public void verify() {
        table.readNodes();
    }

    private int readLastId() {
        if (!Files.exists(sequenceFile)) {
            return 0;
        }
        try {
            JsonNode node = Json.MAPPER.readTree(sequenceFile.toFile());
            return node.path("rules").asInt(0);
        } catch (RuntimeException e) {
            throw new StorageException("Cannot read " + sequenceFile.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
