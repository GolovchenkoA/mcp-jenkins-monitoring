package com.jenkinsmonitoring.config;

import com.jenkinsmonitoring.JenkinsMonitoringApplication;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.nio.file.Path;

/**
 * The folder that holds the application: the directory of the jar, or the working directory when the
 * application is not started from a jar (IDE, tests). Spring resolves relative paths against the working
 * directory, so everything that must sit next to the jar has to go through here.
 */
public final class AppHome {

    private static final String CONFIG_LOCATION_PROPERTY = "spring.config.additional-location";
    private static final String CONFIG_LOCATION_ENV = "SPRING_CONFIG_ADDITIONAL_LOCATION";

    private AppHome() {
    }

    public static Path directory() {
        File source = new ApplicationHome(JenkinsMonitoringApplication.class).getSource();
        if (source != null && source.isFile()) {
            return source.getParentFile().toPath().toAbsolutePath();
        }
        return Path.of("").toAbsolutePath();
    }

    /**
     * Makes an application.properties beside the jar override the packaged defaults, so servers can be
     * configured without rebuilding. An explicit location given by the user is left untouched.
     */
    public static void registerExternalConfigLocation() {
        if (System.getProperty(CONFIG_LOCATION_PROPERTY) != null || System.getenv(CONFIG_LOCATION_ENV) != null) {
            return;
        }
        String folder = directory().toString().replace('\\', '/');
        System.setProperty(CONFIG_LOCATION_PROPERTY, "optional:file:" + folder + "/");
    }
}
