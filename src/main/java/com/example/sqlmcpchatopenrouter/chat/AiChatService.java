package com.example.sqlmcpchatopenrouter.chat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
import com.example.sqlmcpchatopenrouter.schema.SchemaCatalog;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

@Service
public class AiChatService implements AiChatOperations {

    private static final Logger logger = LoggerFactory.getLogger(AiChatService.class);

    /** Brief pause before the single primary retry, so a 429 has a chance to clear. */
    private static final long RETRY_BACKOFF_MILLIS = 1_200L;

    private final ChatClient chatClient;

    private final McpToolCatalog mcpToolCatalog;

    private final ChatMemory chatMemory;

    private final AppProperties properties;

    private final SchemaCatalog schemaCatalog;

    private final SensitiveDataGuard sensitiveDataGuard;

    private final String systemPromptTemplate;

    private volatile String resolvedSystemPrompt;

    private final StructuredAnswerConverter answerConverter = new StructuredAnswerConverter();

    public AiChatService(ChatClient.Builder chatClientBuilder, McpToolCatalog mcpToolCatalog, ChatMemory chatMemory,
            AppProperties properties, SchemaCatalog schemaCatalog, SensitiveDataGuard sensitiveDataGuard,
            @Value("classpath:/prompts/sql-assistant-system.st") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatMemory = chatMemory;
        this.properties = properties;
        this.schemaCatalog = schemaCatalog;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.systemPromptTemplate = read(systemPrompt);
    }

    public ChatResult chat(String message, String conversationId) {
        return chat(message, conversationId, ProgressSink.none());
    }

    @Override
    public ChatResult chat(String message, String conversationId, ProgressSink progressSink) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId
                : UUID.randomUUID().toString();

        progressSink.progress("schema", "Loading schema and relationships\u2026");
        String system = systemPrompt();

        SensitiveDataGuard.Session guardSession =
                this.sensitiveDataGuard.newSession(step -> progressSink.progress("tool", step));
        ToolCallback[] tools = guardSession.wrap(this.mcpToolCatalog.toolCallbacksOrEmpty());
        progressSink.progress("mcp", "Connected to DAB (" + tools.length + " tools).");

        String primary = this.properties.models().primary();
        String fallback = this.properties.models().fallback();

        try {
            progressSink.progress("model", "Reasoning with " + primary + "\u2026");
            return complete(message, resolvedConversationId, primary, system, tools, guardSession, false);
        }
        catch (RuntimeException firstFailure) {
            logger.warn("Primary model call failed; retrying once. model={} cause={}", primary,
                    firstFailure.getClass().getSimpleName());
            progressSink.progress("retry", "Primary model failed \u2014 retrying once\u2026");
            sleepBeforeRetry();
            try {
                return complete(message, resolvedConversationId, primary, system, tools, guardSession, false);
            }
            catch (RuntimeException retryFailure) {
                if (!this.properties.execution().fallbackEnabled()) {
                    throw chatFailure("Primary model failed after one retry.", retryFailure);
                }
                logger.warn("Primary retry failed; falling back. model={} cause={}", fallback,
                        retryFailure.getClass().getSimpleName());
                progressSink.progress("fallback", "Falling back to " + fallback + "\u2026");
                try {
                    return complete(message, resolvedConversationId, fallback, system, tools, guardSession, true);
                }
                catch (RuntimeException fallbackFailure) {
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

    /** Schema is read once and spliced into the prompt; empty when the database is unreachable. */
    private String systemPrompt() {
        String cached = this.resolvedSystemPrompt;
        if (cached == null) {
            synchronized (this) {
                if (this.resolvedSystemPrompt == null) {
                    String schema = this.schemaCatalog.render();
                    this.resolvedSystemPrompt = this.systemPromptTemplate
                            .replace("__CATALOG__", this.properties.schema().catalog())
                            .replace("__SCHEMA__", schema.isBlank()
                                    ? "(schema unavailable - call describe_entities first)" : schema);
                }
                cached = this.resolvedSystemPrompt;
            }
        }
        return cached;
    }

    private ChatResult complete(String message, String conversationId, String model, String system,
            ToolCallback[] tools, SensitiveDataGuard.Session guardSession, boolean fallbackUsed) {
        long startedAt = System.nanoTime();
        ResponseEntity<ChatResponse, RawAssistantAnswer> response = this.chatClient.prompt()
                .system(system)
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(chatOptions(model, tools))
                .call()
                .responseEntity(this.answerConverter);

        logUsage(model, response.response(), System.nanoTime() - startedAt);

        // Tokens go out to the model; real values come back only here, at the edge.
        AssistantAnswer raw = AssistantAnswer.from(response.entity());
        // usedDatabaseTools is measured, not taken on trust: models have been observed
        // claiming tool use while answering from memory.
        AssistantAnswer restored = new AssistantAnswer(guardSession.detokenize(raw.answer()),
                raw.columns(),
                raw.rows().stream().map(row -> row.stream().map(guardSession::detokenize).toList()).toList(),
                guardSession.toolInvocations() > 0, raw.partialResults(),
                guardSession.detokenize(raw.dataNotes()), guardSession.detokenize(raw.followUpQuestion()));
        return new ChatResult(conversationId, model, fallbackUsed, restored);
    }

    private static void logUsage(String model, ChatResponse response, long elapsedNanos) {
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
