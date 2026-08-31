package com.example.sqlmcpchatopenrouter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SensitiveLoggingPolicyTests {

    @Test
    void sensitiveLoggingDefaultsToFalse() {
        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(false), new MockEnvironment());

        assertThat(policy.sensitiveLoggingEnabled()).isFalse();
    }

    @Test
    void sensitiveLoggingRequiresLocalOrDevProfile() {
        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(true), new MockEnvironment());

        assertThat(policy.sensitiveLoggingEnabled()).isFalse();
    }

    @Test
    void sensitiveLoggingCanBeEnabledForLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SensitiveLoggingPolicy policy = new SensitiveLoggingPolicy(properties(true), environment);

        assertThat(policy.sensitiveLoggingEnabled()).isTrue();
    }

    private static AppProperties properties(boolean logSensitiveData) {
        return new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, Duration.ofSeconds(10),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Openrouter("", "test"),
                new AppProperties.Security("unit-test-secret"),
                new AppProperties.Logging(logSensitiveData),
                List.of());
    }
}
