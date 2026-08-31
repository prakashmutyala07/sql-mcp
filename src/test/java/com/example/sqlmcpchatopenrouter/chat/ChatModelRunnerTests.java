package com.example.sqlmcpchatopenrouter.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

import tools.jackson.databind.json.JsonMapper;

class ChatModelRunnerTests {

    private static final String VALID_ANSWER = """
            {"status":"ANSWER","answer":"Done.","columns":[],"rows":[],
            "partialResults":false,"dataNotes":"","followUpQuestion":""}
            """;

    @Test
    void primaryFailureFallsBackImmediatelyWhenPrimaryRetryIsDisabled() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> {
            String model = model(prompt);
            calls.add(model);
            if (model.equals("primary")) {
                throw new IllegalStateException("provider failure");
            }
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        assertThat(calls).containsExactly("primary", "fallback");
        assertThat(result.model()).isEqualTo("fallback");
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    void primaryRetryHappensOnlyWhenPrimaryRetryIsEnabled() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(true, AppProperties.ResponseFormat.JSON_SCHEMA, prompt -> {
            String model = model(prompt);
            calls.add(model);
            if (calls.size() == 1) {
                throw new IllegalStateException("transient provider failure");
            }
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        assertThat(calls).containsExactly("primary", "primary");
        assertThat(result.model()).isEqualTo("primary");
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void structuredOutputParseFailureReturnsSafeErrorResponse() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.JSON_SCHEMA,
                prompt -> response("not-json jane.doe@example.com 415-555-0101"));

        ChatModelRunner.Result result = fixture.run();

        assertThat(result.answer().status()).isEqualTo(ChatResponse.Status.ERROR);
        assertThat(result.answer().answer()).contains("couldn't safely interpret")
                .doesNotContain("jane.doe@example.com", "415-555-0101");
        assertThat(result.answer().columns()).isEmpty();
        assertThat(result.answer().rows()).isEmpty();
    }

    @Test
    void promptJsonOmitsProviderResponseFormatAndStillParsesTypedResponse() {
        Fixture fixture = fixture(false, AppProperties.ResponseFormat.PROMPT_JSON, prompt -> {
            assertThat(prompt.getSystemMessage().getText()).contains("JSON Schema instance");
            return response(VALID_ANSWER);
        });

        ChatModelRunner.Result result = fixture.run();

        OpenAiChatOptions options = fixture.runner().chatOptions("primary", new ToolCallback[0]).build();
        assertThat(options.getResponseFormat()).isNull();
        assertThat(result.answer().status()).isEqualTo(ChatResponse.Status.ANSWER);
    }

    private static Fixture fixture(boolean primaryRetryEnabled, AppProperties.ResponseFormat responseFormat,
            Function<Prompt, org.springframework.ai.chat.model.ChatResponse> responder) {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, primaryRetryEnabled, 1200, 0.1, Duration.ofSeconds(10),
                        responseFormat),
                new AppProperties.Memory(20), new AppProperties.Openrouter("", "test"),
                new AppProperties.Security("unit-test-secret"), List.of());
        SensitiveDataGuard guard = new SensitiveDataGuard(properties, JsonMapper.builder().build());
        ChatModel model = responder::apply;
        ChatModelRunner runner = new ChatModelRunner(ChatClient.builder(model), properties);
        return new Fixture(runner, guard.newSession());
    }

    private static String model(Prompt prompt) {
        return prompt.getOptions().getModel();
    }

    private static org.springframework.ai.chat.model.ChatResponse response(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(content))));
    }

    private record Fixture(ChatModelRunner runner, SensitiveDataGuard.Session guardSession) {

        private ChatModelRunner.Result run() {
            return this.runner.run("question", "system", List.of(), new ToolCallback[0], this.guardSession,
                    ProgressSink.none());
        }
    }
}
