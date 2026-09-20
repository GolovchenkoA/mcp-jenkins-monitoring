package com.jenkinsmonitoring.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppHomeTest {

    private static final String PROPERTY = "spring.config.additional-location";

    private String original;

    @BeforeEach
    void remember() {
        original = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restore() {
        if (original == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, original);
        }
    }

    @Test
    void anApplicationPropertiesBesideTheJarIsLoadedAsAnOptionalLocation() {
        AppHome.registerExternalConfigLocation();

        String location = System.getProperty(PROPERTY);
        assertThat(location).startsWith("optional:file:").endsWith("/");
        assertThat(location).contains(AppHome.directory().toString().replace('\\', '/'));
    }

    @Test
    void aLocationChosenByTheUserIsNotReplaced() {
        System.setProperty(PROPERTY, "optional:file:/custom/");

        AppHome.registerExternalConfigLocation();

        assertThat(System.getProperty(PROPERTY)).isEqualTo("optional:file:/custom/");
    }
}
