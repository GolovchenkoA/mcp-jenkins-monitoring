package com.jenkinsmonitoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** A bean so tests can replace the time source. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
