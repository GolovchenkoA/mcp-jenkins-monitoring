package com.jenkinsmonitoring.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns relative file locations into absolute ones next to the jar. It runs after the configuration files
 * are loaded and before logging starts, so {@code logging.file.name=${files.root.folder}/...} in the debug
 * profile also ends up next to the jar. Absolute values (for example set by a user or a test) stay as they are.
 */
public class RootFolderEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final List<String> FILE_PROPERTIES = List.of("files.root.folder", "files.storage.path");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> absolute = new HashMap<>();
        for (String key : FILE_PROPERTIES) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                absolute.put(key, AppHome.directory().resolve(path).normalize().toString());
            }
        }
        if (!absolute.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("absoluteFileLocations", absolute));
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }
}
