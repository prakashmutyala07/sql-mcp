package com.example.sqlmcpchatopenrouter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

class AiConfigurationTests {

    @Test
    void chatMemoryBeanUsesConfiguredWindow() {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, 1200, 0.1, java.time.Duration.ofSeconds(120)),
                new AppProperties.Memory(3),
                new AppProperties.Openrouter("", "title"),
                new AppProperties.Security("secret"),
                new AppProperties.Schema(false, "dbo", "", "", ""),
                java.util.List.of());

        ChatMemory chatMemory = new AiConfiguration().chatMemory(properties);

        assertThat(chatMemory).isNotNull();
    }
}
