package com.jenkinsmonitoring;

import com.jenkinsmonitoring.config.AppHome;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JenkinsMonitoringApplication {

    public static void main(String[] args) {
        AppHome.registerExternalConfigLocation();
        SpringApplication.run(JenkinsMonitoringApplication.class, args);
    }
}
