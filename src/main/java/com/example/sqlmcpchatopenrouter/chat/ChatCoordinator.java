package com.example.sqlmcpchatopenrouter.chat;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.SensitiveLoggingPolicy;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

/** Coordinates a chat turn; specialized collaborators own prompts, privacy, tools, and model policy. */
@Service
public class ChatCoordinator implements ChatOperations {

    private static final Logger logger = LoggerFactory.getLogger(ChatCoordinator.class);

    private final McpToolCatalog mcpToolCatalog;

    private final ChatMemory chatMemory;

    private final SensitiveDataGuard sensitiveDataGuard;

    private final PromptProvider promptProvider;

    private final ChatModelRunner modelRunner;

    private final SensitiveLoggingPolicy sensitiveLoggingPolicy;

    public ChatCoordinator(McpToolCatalog mcpToolCatalog, ChatMemory chatMemory,
            SensitiveDataGuard sensitiveDataGuard, PromptProvider promptProvider, ChatModelRunner modelRunner,
            SensitiveLoggingPolicy sensitiveLoggingPolicy) {
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatMemory = chatMemory;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.promptProvider = promptProvider;
        this.modelRunner = modelRunner;
        this.sensitiveLoggingPolicy = sensitiveLoggingPolicy;
    }

    @Override
    public ChatResponse chat(String message, String conversationId) {
        return chat(message, conversationId, ProgressSink.none());
    }

    @Override
    public ChatResponse chat(String message, String conversationId, ProgressSink progressSink) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId
                : UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long requestStartedAt = System.nanoTime();
        logger.info("[CHAT_REQUEST] requestId={} conversationId={} request started inputChars={}",
                requestId, resolvedConversationId, message == null ? 0 : message.length());
        if (this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()) {
            logger.info("[CHAT_REQUEST] requestId={} rawUserInput={}", requestId, message);
        }

        progressSink.progress("prepare", "Preparing a safe database request\u2026");
        SensitiveDataGuard.Session guardSession =
                this.sensitiveDataGuard.newSession(requestId, step -> progressSink.progress("tool", step));
        String protectedMessage = guardSession.protectInput(message);
        ToolCallback[] tools = guardSession.wrap(this.mcpToolCatalog.toolCallbacksOrEmpty());
        progressSink.progress("mcp", "Connected to DAB (" + tools.length + " tools).");

        List<Message> history = this.chatMemory.get(resolvedConversationId);
        logger.info("[MEMORY_CONTEXT] requestId={} conversationId={} sanitized=true messages={}",
                requestId, resolvedConversationId, history.size());
        if (this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()) {
            logger.info("[MEMORY_CONTEXT] requestId={} sanitizedHistory={}", requestId,
                    history.stream().map(Message::getText).toList());
        }
        try {
            ChatModelRunner.Result result = this.modelRunner.run(protectedMessage, this.promptProvider.systemPrompt(),
                    history, tools, guardSession, progressSink, requestId);
            long structuredStartedAt = System.nanoTime();
            ChatResponse response = ChatResponse.from(resolvedConversationId, result.model(), result.fallbackUsed(),
                    result.answer(), guardSession.toolInvocations() > 0);
            logger.info("[STRUCTURED_OUTPUT] requestId={} conversationId={} status={} rows={} durationMs={}",
                    requestId, resolvedConversationId, response.status(), response.rows().size(),
                    elapsedMillis(structuredStartedAt));
            if (this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()) {
                logger.info("[STRUCTURED_OUTPUT] requestId={} chatResponse={}", requestId, response);
            }
            storeSanitizedTurn(resolvedConversationId, protectedMessage, response);
            logger.info("[MEMORY_WRITE] requestId={} conversationId={} sanitized=true messagesWritten=2",
                    requestId, resolvedConversationId);
            if (this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()) {
                logger.info("[MEMORY_WRITE] requestId={} contentConsidered={} sanitizedUserMessage={} "
                        + "sanitizedAssistantMemory={}", requestId, response, protectedMessage,
                        memoryContent(response));
            }
            logger.info("[CHAT_RESPONSE] requestId={} conversationId={} completed status={} durationMs={}",
                    requestId, resolvedConversationId, response.status(), elapsedMillis(requestStartedAt));
            if (this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()) {
                logger.info("[CHAT_RESPONSE] requestId={} finalResponse={}", requestId, response);
            }
            return response;
        }
        catch (RuntimeException ex) {
            logger.error("[CHAT_RESPONSE] requestId={} conversationId={} failed errorType={} durationMs={}",
                    requestId, resolvedConversationId, ex.getClass().getSimpleName(),
                    elapsedMillis(requestStartedAt));
            throw ex;
        }
        finally {
            progressSink.progress("done", "Composing answer\u2026");
        }
    }

    @Override
    public void clearMemory(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            this.chatMemory.clear(conversationId);
        }
    }

    @Override
    public List<McpToolCatalog.ToolSummary> tools() {
        return this.mcpToolCatalog.tools();
    }

    private void storeSanitizedTurn(String conversationId, String protectedMessage, ChatResponse response) {
        this.chatMemory.add(conversationId, new UserMessage(protectedMessage));
        this.chatMemory.add(conversationId, new AssistantMessage(memoryContent(response)));
    }

    private static String memoryContent(ChatResponse response) {
        return "status=" + response.status() + "; answer=" + response.message()
                + "; columns=" + response.columns() + "; rows=" + response.rows()
                + "; dataNotes=" + response.dataNotes() + "; followUpQuestion=" + response.followUpQuestion();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
