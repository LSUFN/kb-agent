package com.example.kbagent.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §7：重试退避计算。§4.2：min(30s × 2^attempt, 10min)。 */
class TaskPropertiesTest {

    private final TaskProperties properties = new TaskProperties(true, Duration.ofSeconds(5), Duration.ofMinutes(5), 2,
            Duration.ofSeconds(30), Duration.ofMinutes(10), true);

    @ParameterizedTest
    @CsvSource({"1, 60", "2, 120", "3, 240", "4, 480", "5, 600", "30, 600"})
    void retryDelayDoublesUntilTheCap(int attempt, long expectedSeconds) {
        assertThat(properties.retryDelay(attempt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void hugeAttemptNumbersDoNotOverflow() {
        assertThat(properties.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.retryDelay(-1)).isEqualTo(Duration.ofSeconds(30));
    }
}
