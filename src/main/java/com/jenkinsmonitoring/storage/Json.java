package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mappers for the storage files and for tool results: snake_case names and empty values left out.
 * They are deliberately not Spring beans, so they cannot replace the mapper Spring itself uses.
 */
public final class Json {

    /** Indented with LF on every operating system, for files and tool results that people read. */
    public static final JsonMapper MAPPER = base()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    /** One line per value, for log lines. */
    public static final JsonMapper COMPACT = base().build();

    private Json() {
    }

    private static JsonMapper.Builder base() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_EMPTY));
    }
}
