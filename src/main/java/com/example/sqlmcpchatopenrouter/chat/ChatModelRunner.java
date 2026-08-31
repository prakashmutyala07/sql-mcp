package com.example.sqlmcpchatopenrouter.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

/** Owns model invocation policy, OpenRouter options, and structured-output conversion. */
@Component
public class ChatModelRunner {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelRunner.class);

    private static final long RETRY_BACKOFF_MILLIS = 1_200L;

    private static final String STRUCTURED_OUTPUT_ERROR =
            "I couldn't safely interpret the model response. Please try again.";

    private final ChatClient chatClient;

    private final AppProperties properties;

    private final BeanOutputConverter<ChatResponse.ModelAnswer> answerConverter =
            new BeanOutputConverter<>(ChatResponse.ModelAnswer.class);

    public ChatModelRunner(ChatClient.Builder chatClientBuilder, AppProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
    }

    public Result run(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            SensitiveDataGuard.Session guardSession, ProgressSink progressSink) {
        String primary = this.properties.models().primary();
        String fallback = this.properties.models().fallback();

        try {
            progressSink.progress("model", "Reasoning with " + primary + "\u2026");
            return complete(message, systemPrompt, history, tools, guardSession, primary, false);
        }
        catch (ModelCallException firstFailure) {
            logModelFailure(firstFailure);
            if (this.properties.execution().primaryRetryEnabled()) {
                progressSink.progress("retry", "Primary model failed \u2014 retrying once\u2026");
                sleepBeforeRetry();
                try {
                    return complete(message, systemPrompt, history, tools, guardSession, primary, false);
                }
                catch (ModelCallException retryFailure) {
                    logModelFailure(retryFailure);
                    return fallbackOrThrow(message, systemPrompt, history, tools, guardSession, progressSink,
                            fallback, "Primary model failed after one retry.");
                }
            }
            return fallbackOrThrow(message, systemPrompt, history, tools, guardSession, progressSink,
                    fallback, "Primary model failed.");
        }
    }

    private Result fallbackOrThrow(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            SensitiveDataGuard.Session guardSession, ProgressSink progressSink, String fallback,
            String primaryFailureMessage) {
        if (!this.properties.execution().fallbackEnabled()) {
            throw chatFailure(primaryFailureMessage);
        }
        progressSink.progress("fallback", "Falling back to " + fallback + "\u2026");
        try {
            return complete(message, systemPrompt, history, tools, guardSession, fallback, true);
        }
        catch (ModelCallException fallbackFailure) {
            logModelFailure(fallbackFailure);
            throw chatFailure("Primary and fallback models both failed.");
        }
    }

    private Result complete(String message, String systemPrompt, List<Message> history, ToolCallback[] tools,
            SensitiveDataGuard.Session guardSession, String model, boolean fallbackUsed) {
        long startedAt = System.nanoTime();
        org.springframework.ai.chat.model.ChatResponse response;
        try {
            response = this.chatClient.prompt()
                    .system(systemPrompt(systemPrompt))
                    .messages(history)
                    .user(message)
                    .options(chatOptions(model, tools))
                    .call()
                    .chatResponse();
        }
        catch (RuntimeException ex) {
            throw new ModelCallException(model, ex, elapsedMillis(startedAt));
        }

        logUsage(model, response, System.nanoTime() - startedAt);
        String content = response == null || response.getResult() == null
                || response.getResult().getOutput() == null ? null : response.getResult().getOutput().getText();
        ChatResponse.ModelAnswer parsed;
        try {
            parsed = this.answerConverter.convert(content);
        }
        catch (RuntimeException ex) {
            logger.warn("Structured model response could not be parsed. model={} exception={} elapsedMs={}", model,
                    ex.getClass().getSimpleName(), elapsedMillis(startedAt));
            parsed = new ChatResponse.ModelAnswer(ChatResponse.Status.ERROR, STRUCTURED_OUTPUT_ERROR,
                    List.of(), List.of(), false, "", "");
        }
        return new Result(model, fallbackUsed, sanitize(parsed, guardSession));
    }

    private static ChatResponse.ModelAnswer sanitize(ChatResponse.ModelAnswer answer,
            SensitiveDataGuard.Session guardSession) {
        if (answer == null) {
            return new ChatResponse.ModelAnswer(ChatResponse.Status.ERROR, STRUCTURED_OUTPUT_ERROR,
                    List.of(), List.of(), false, "", "");
        }
        return new ChatResponse.ModelAnswer(answer.status(), guardSession.protectOutput(answer.answer()),
                answer.columns().stream().map(guardSession::protectOutput).toList(),
                answer.rows().stream()
                        .map(row -> row.stream().map(guardSession::protectOutput).toList())
                        .toList(),
                answer.partialResults(), guardSession.protectOutput(answer.dataNotes()),
                guardSession.protectOutput(answer.followUpQuestion()));
    }

    OpenAiChatOptions.Builder chatOptions(String model, ToolCallback[] tools) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(this.properties.execution().temperature())
                .maxCompletionTokens(this.properties.execution().maxCompletionTokens())
                .toolCallbacks(tools)
                .parallelToolCalls(false)
                .timeout(this.properties.execution().requestTimeout())
                .maxRetries(0)
                .customHeaders(openRouterHeaders());
        if (this.properties.execution().responseFormat() == AppProperties.ResponseFormat.JSON_SCHEMA) {
            options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(this.answerConverter.getJsonSchema())
                    .strict(Boolean.FALSE)
                    .build());
        }
        return options;
    }

    private String systemPrompt(String systemPrompt) {
        if (this.properties.execution().responseFormat() == AppProperties.ResponseFormat.PROMPT_JSON) {
            return systemPrompt + "\n\n" + this.answerConverter.getFormat();
        }
        return systemPrompt;
    }

    private Map<String, String> openRouterHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (StringUtils.hasText(this.properties.openrouter().referer())) {
            headers.put("HTTP-Referer", this.properties.openrouter().referer());
        }
        if (StringUtils.hasText(this.properties.openrouter().title())) {
            headers.put("X-Title", this.properties.openrouter().title());
        }
        return headers;
    }

    private static void logUsage(String model, org.springframework.ai.chat.model.ChatResponse response,
            long elapsedNanos) {
        long latencyMs = elapsedNanos / 1_000_000L;
        Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage == null) {
            logger.info("model={} latencyMs={} tokens=unavailable", model, latencyMs);
            return;
        }
        logger.info("model={} latencyMs={} promptTokens={} completionTokens={} totalTokens={}", model, latencyMs,
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static void logModelFailure(ModelCallException failure) {
        logger.warn("Model call failed. model={} exception={} elapsedMs={}", failure.model(),
                failure.getCause().getClass().getSimpleName(), failure.elapsedMs());
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ResponseStatusException chatFailure(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    public record Result(String model, boolean fallbackUsed, ChatResponse.ModelAnswer answer) {
    }

    private static final class ModelCallException extends RuntimeException {

        private final String model;

        private final long elapsedMs;

        private ModelCallException(String model, RuntimeException cause, long elapsedMs) {
            super(cause);
            this.model = model;
            this.elapsedMs = elapsedMs;
        }

        private String model() {
            return this.model;
        }

        private long elapsedMs() {
            return this.elapsedMs;
        }
    }
}
