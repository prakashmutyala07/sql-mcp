package com.example.sqlmcpchatopenrouter.chat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

@Service
public class ChatCoordinator implements ChatOperations {

    private static final Logger logger = LoggerFactory.getLogger(ChatCoordinator.class);

    /** Brief pause before the single primary retry, so a 429 has a chance to clear. */
    private static final long RETRY_BACKOFF_MILLIS = 1_200L;

    private final ChatClient chatClient;

    private final McpToolCatalog mcpToolCatalog;

    private final ChatMemory chatMemory;

    private final AppProperties properties;

    private final SensitiveDataGuard sensitiveDataGuard;

    private final String systemPromptTemplate;

    private final BeanOutputConverter<ChatResponse.ModelAnswer> answerConverter =
            new BeanOutputConverter<>(ChatResponse.ModelAnswer.class);

    public ChatCoordinator(ChatClient.Builder chatClientBuilder, McpToolCatalog mcpToolCatalog, ChatMemory chatMemory,
            AppProperties properties, SensitiveDataGuard sensitiveDataGuard,
            @Value("classpath:/prompts/sql-assistant-system.st") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatMemory = chatMemory;
        this.properties = properties;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.systemPromptTemplate = read(systemPrompt);
    }

    public ChatResponse chat(String message, String conversationId) {
        return chat(message, conversationId, ProgressSink.none());
    }

    @Override
    public ChatResponse chat(String message, String conversationId, ProgressSink progressSink) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId
                : UUID.randomUUID().toString();

        progressSink.progress("schema", "Loading schema and relationships\u2026");
        String system = this.systemPromptTemplate;

        SensitiveDataGuard.Session guardSession =
                this.sensitiveDataGuard.newSession(step -> progressSink.progress("tool", step));
        String protectedMessage = guardSession.protectInput(message);
        ToolCallback[] tools = guardSession.wrap(this.mcpToolCatalog.toolCallbacksOrEmpty());
        progressSink.progress("mcp", "Connected to DAB (" + tools.length + " tools).");

        List<Message> memoryBeforeTurn = this.chatMemory.get(resolvedConversationId);

        String primary = this.properties.models().primary();
        String fallback = this.properties.models().fallback();

        try {
            progressSink.progress("model", "Reasoning with " + primary + "\u2026");
            return complete(protectedMessage, resolvedConversationId, primary, system, tools, guardSession, false);
        }
        catch (RuntimeException firstFailure) {
            restoreMemory(resolvedConversationId, memoryBeforeTurn);
            logger.warn("Primary model call failed; retrying once. model={} cause={}", primary,
                    firstFailure.getClass().getSimpleName());
            progressSink.progress("retry", "Primary model failed \u2014 retrying once\u2026");
            sleepBeforeRetry();
            try {
                return complete(protectedMessage, resolvedConversationId, primary, system, tools, guardSession, false);
            }
            catch (RuntimeException retryFailure) {
                restoreMemory(resolvedConversationId, memoryBeforeTurn);
                if (!this.properties.execution().fallbackEnabled()) {
                    throw chatFailure("Primary model failed after one retry.", retryFailure);
                }
                logger.warn("Primary retry failed; falling back. model={} cause={}", fallback,
                        retryFailure.getClass().getSimpleName());
                progressSink.progress("fallback", "Falling back to " + fallback + "\u2026");
                try {
                    return complete(protectedMessage, resolvedConversationId, fallback, system, tools, guardSession,
                            true);
                }
                catch (RuntimeException fallbackFailure) {
                    restoreMemory(resolvedConversationId, memoryBeforeTurn);
                    fallbackFailure.addSuppressed(retryFailure);
                    throw chatFailure("Primary and fallback models both failed.", fallbackFailure);
                }
            }
        }
        finally {
            progressSink.progress("done", "Composing answer\u2026");
        }
    }

    public void clearMemory(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            this.chatMemory.clear(conversationId);
        }
    }

    @Override
    public List<McpToolCatalog.ToolSummary> tools() {
        return this.mcpToolCatalog.tools();
    }

    private ChatResponse complete(String message, String conversationId, String model, String system,
            ToolCallback[] tools, SensitiveDataGuard.Session guardSession, boolean fallbackUsed) {
        long startedAt = System.nanoTime();
        ResponseEntity<org.springframework.ai.chat.model.ChatResponse, ChatResponse.ModelAnswer> response =
                this.chatClient.prompt()
                .system(system)
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(chatOptions(model, tools))
                .call()
                .responseEntity(this.answerConverter);

        logUsage(model, response.response(), System.nanoTime() - startedAt);

        ChatResponse.ModelAnswer raw = response.entity();
        ChatResponse.ModelAnswer restored = new ChatResponse.ModelAnswer(
                raw == null ? ChatResponse.Status.ERROR : raw.status(),
                raw == null ? "" : guardSession.protectOutput(raw.answer()),
                raw == null ? java.util.List.of() : raw.columns(),
                raw == null ? java.util.List.of()
                        : raw.rows().stream()
                                .map(row -> row.stream().map(guardSession::protectOutput).toList())
                                .toList(),
                raw != null && raw.partialResults(),
                raw == null ? "" : guardSession.protectOutput(raw.dataNotes()),
                raw == null ? "" : guardSession.protectOutput(raw.followUpQuestion()));
        // Tool use is measured, not taken on trust from model output.
        return ChatResponse.from(conversationId, model, fallbackUsed, restored, guardSession.toolInvocations() > 0);
    }

    private static void logUsage(String model, org.springframework.ai.chat.model.ChatResponse response,
            long elapsedNanos) {
        long latencyMs = elapsedNanos / 1_000_000L;
        Usage usage = (response != null && response.getMetadata() != null) ? response.getMetadata().getUsage() : null;
        if (usage == null) {
            logger.info("model={} latencyMs={} tokens=unavailable", model, latencyMs);
            return;
        }
        logger.info("model={} latencyMs={} promptTokens={} completionTokens={} totalTokens={}", model, latencyMs,
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private OpenAiChatOptions.Builder chatOptions(String model, ToolCallback[] tools) {
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(this.properties.execution().temperature())
                .maxCompletionTokens(this.properties.execution().maxCompletionTokens())
                .toolCallbacks(tools)
                .parallelToolCalls(false)
                .timeout(this.properties.execution().requestTimeout())
                // This service owns retry semantics (retry once, then fall back). The SDK's own
                // retries would silently multiply against that and stretch a stall past any budget.
                .maxRetries(0)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(this.answerConverter.getJsonSchema())
                        .strict(Boolean.FALSE)
                        .build())
                .customHeaders(openRouterHeaders());
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

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void restoreMemory(String conversationId, List<Message> messages) {
        this.chatMemory.clear(conversationId);
        if (!messages.isEmpty()) {
            this.chatMemory.add(conversationId, messages);
        }
    }

    private static String read(Resource resource) {
        try {
            return FileCopyUtils.copyToString(new java.io.InputStreamReader(resource.getInputStream(),
                    StandardCharsets.UTF_8));
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to read system prompt", ex);
        }
    }

    private ResponseStatusException chatFailure(String message, RuntimeException failure) {
        logger.warn("{} cause={}", message, failure.getClass().getSimpleName());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

}
