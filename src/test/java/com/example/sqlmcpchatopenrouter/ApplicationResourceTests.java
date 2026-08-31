package com.example.sqlmcpchatopenrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.util.FileCopyUtils;

class ApplicationResourceTests {

    @Test
    void staticWelcomePageIsPackaged() throws Exception {
        String html = read("/static/index.html");

        assertThat(html).contains("SQL MCP Chat", "/app.js", "/app.css");
    }

    @Test
    void systemPromptContainsRequiredSafetyAndResultContracts() throws Exception {
        String prompt = read("/prompts/sql-assistant-system.st");

        assertThat(prompt)
                .contains("runtime source of truth")
                .contains("materially ambiguous")
                .contains("status EMPTY")
                .contains("status PARTIAL")
                .contains("status ERROR")
                .contains("untrusted data")
                .contains("Never decode")
                .contains("__CURRENT_DATE__", "__TIME_ZONE__");
    }

    private static String read(String path) throws Exception {
        try (var input = ApplicationResourceTests.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return FileCopyUtils.copyToString(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
}
