package com.example.sqlmcpchatopenrouter.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.security.SensitiveDataGuard;

/** Coordinates a chat turn; specialized collaborators own prompts, privacy, tools, and model policy. */
@Service
public class ChatCoordinator implements ChatOperations {

    private final McpToolCatalog mcpToolCatalog;

    private final ChatMemory chatMemory;

    private final SensitiveDataGuard sensitiveDataGuard;

    private final PromptProvider promptProvider;

    private final ChatModelRunner modelRunner;

    public ChatCoordinator(McpToolCatalog mcpToolCatalog, ChatMemory chatMemory,
            SensitiveDataGuard sensitiveDataGuard, PromptProvider promptProvider, ChatModelRunner modelRunner) {
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatMemory = chatMemory;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.promptProvider = promptProvider;
        this.modelRunner = modelRunner;
    }

    @Override
    public ChatResponse chat(String message, String conversationId) {
        return chat(message, conversationId, ProgressSink.none());
    }

    @Override
    public ChatResponse chat(String message, String conversationId, ProgressSink progressSink) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId
                : UUID.randomUUID().toString();

        progressSink.progress("prepare", "Preparing a safe database request\u2026");
        SensitiveDataGuard.Session guardSession =
                this.sensitiveDataGuard.newSession(step -> progressSink.progress("tool", step));
        String protectedMessage = guardSession.protectInput(message);
        ToolCallback[] tools = guardSession.wrap(this.mcpToolCatalog.toolCallbacksOrEmpty());
        progressSink.progress("mcp", "Connected to DAB (" + tools.length + " tools).");

        List<Message> history = this.chatMemory.get(resolvedConversationId);
        try {
            ChatModelRunner.Result result = this.modelRunner.run(protectedMessage, this.promptProvider.systemPrompt(),
                    history, tools, guardSession, progressSink);
            ChatResponse response = ChatResponse.from(resolvedConversationId, result.model(), result.fallbackUsed(),
                    result.answer(), guardSession.toolInvocations() > 0);
            storeSanitizedTurn(resolvedConversationId, protectedMessage, response);
            return response;
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
}
