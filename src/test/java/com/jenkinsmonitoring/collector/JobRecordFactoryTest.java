package com.jenkinsmonitoring.collector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobRecordFactoryTest {

    @Test
    void durationsAreReadable() {
        assertThat(JobRecordFactory.formatDuration(null)).isNull();
        assertThat(JobRecordFactory.formatDuration(0L)).isEqualTo("<1s");
        assertThat(JobRecordFactory.formatDuration(999L)).isEqualTo("<1s");
        assertThat(JobRecordFactory.formatDuration(1_000L)).isEqualTo("1s");
        assertThat(JobRecordFactory.formatDuration(56_432L)).isEqualTo("56s");
        assertThat(JobRecordFactory.formatDuration(185_071L)).isEqualTo("3m 5s");
        assertThat(JobRecordFactory.formatDuration(3_723_000L)).isEqualTo("1h 2m 3s");
        assertThat(JobRecordFactory.formatDuration(90_000_000L)).isEqualTo("25h 0m 0s");
    }
}
