package com.example.sqlmcpchatopenrouter.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

import tools.jackson.databind.json.JsonMapper;

class ChatCoordinatorTests {

    private static final String EMAIL = "jane.doe@example.com";

    private static final String PHONE = "415-555-0101";

    @Test
    void rawInputAndModelOutputNeverReachFinalResponseOrMemory() {
        AtomicReference<Prompt> receivedPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            receivedPrompt.set(prompt);
            return response("""
                    {"status":"ANSWER","answer":"Contact jane.doe@example.com or 415-555-0101.",
                    "columns":["CustomerId","Email"],"rows":[["42","jane.doe@example.com"]],
                    "partialResults":false,"dataNotes":"Phone 415-555-0101","followUpQuestion":""}
                    """);
        };
        Fixture fixture = fixture(model);

        ChatResponse result = fixture.coordinator().chat(
                "Find jane.doe@example.com or call 415-555-0101", "privacy-test");

        assertThat(result.toString()).doesNotContain(EMAIL, PHONE);
        assertThat(result.message()).contains("[REDACTED_EMAIL]", "[REDACTED_PHONE]");
        assertThat(result.rows()).containsExactly(List.of("42", "[REDACTED_EMAIL]"));
        assertThat(receivedPrompt.get().getContents()).doesNotContain(EMAIL, PHONE);
        assertThat(fixture.memory().get("privacy-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(EMAIL, PHONE));
    }

    @Test
    void malformedStructuredOutputReturnsAndStoresSafeError() {
        Fixture fixture = fixture(prompt -> response("not-json jane.doe@example.com 415-555-0101"));

        ChatResponse result = fixture.coordinator().chat("Show orders", "parse-test");

        assertThat(result.status()).isEqualTo(ChatResponse.Status.ERROR);
        assertThat(result.message()).contains("couldn't safely interpret").doesNotContain(EMAIL, PHONE);
        assertThat(fixture.memory().get("parse-test"))
                .extracting(message -> message.getText())
                .allSatisfy(text -> assertThat(text).doesNotContain(EMAIL, PHONE));
    }

    private static Fixture fixture(ChatModel model) {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, 1200, 0.1, Duration.ofSeconds(10)),
                new AppProperties.Memory(20), new AppProperties.Openrouter("", "test"),
                new AppProperties.Security("unit-test-secret"),
                List.of(new AppProperties.SensitiveField("Customer", "Email", "EM"),
                        new AppProperties.SensitiveField("Customer", "Phone", "PH")));
        SensitiveDataGuard guard = new SensitiveDataGuard(properties, JsonMapper.builder().build());
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        PromptProvider prompts = new PromptProvider(new ByteArrayResource(
                "Date __CURRENT_DATE__, zone __TIME_ZONE__.".getBytes(StandardCharsets.UTF_8)));
        ChatModelRunner runner = new ChatModelRunner(ChatClient.builder(model), properties);
        ChatCoordinator coordinator = new ChatCoordinator(new EmptyToolCatalog(), memory, guard, prompts, runner);
        return new Fixture(coordinator, memory);
    }

    private static org.springframework.ai.chat.model.ChatResponse response(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(content))));
    }

    private record Fixture(ChatCoordinator coordinator, ChatMemory memory) {
    }

    private static final class EmptyToolCatalog extends McpToolCatalog {

        private EmptyToolCatalog() {
            super(null);
        }

        @Override
        public ToolCallback[] toolCallbacksOrEmpty() {
            return new ToolCallback[0];
        }

        @Override
        public List<ToolSummary> tools() {
            return List.of();
        }
    }
}
