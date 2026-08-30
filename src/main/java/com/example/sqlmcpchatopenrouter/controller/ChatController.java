package com.example.sqlmcpchatopenrouter.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.sqlmcpchatopenrouter.chat.AiChatOperations;
import com.example.sqlmcpchatopenrouter.chat.AssistantAnswer;
import com.example.sqlmcpchatopenrouter.chat.ChatResult;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.mcp.McpToolOperations;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final AiChatOperations aiChatService;

    private final McpToolOperations mcpToolCatalog;

    private final AsyncTaskExecutor chatTaskExecutor;

    public ChatController(AiChatOperations aiChatService, McpToolOperations mcpToolCatalog,
            AsyncTaskExecutor chatTaskExecutor) {
        this.aiChatService = aiChatService;
        this.mcpToolCatalog = mcpToolCatalog;
        this.chatTaskExecutor = chatTaskExecutor;
    }

    @GetMapping("/mcp/tools")
    public List<McpToolCatalog.ToolSummary> mcpTools() {
        return this.mcpToolCatalog.tools();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        return ResponseEntity.ok(ChatResponse.from(this.aiChatService.chat(request.message(), request.conversationId())));
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        this.chatTaskExecutor.execute(() -> {
            try {
                send(emitter, "progress", new ProgressEvent("accepted", "Request accepted."));
                ChatResult result = this.aiChatService.chat(request.message(), request.conversationId(),
                        (stage, message) -> send(emitter, "progress", new ProgressEvent(stage, message)));
                send(emitter, "complete", ChatResponse.from(result));
                emitter.complete();
            }
            catch (RuntimeException ex) {
                try {
                    send(emitter, "error", new ProgressEvent("error", errorMessage(ex)));
                }
                finally {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    @DeleteMapping("/conversations/{conversationId}/memory")
    public ResponseEntity<Void> clearMemory(@PathVariable String conversationId) {
        this.aiChatService.clearMemory(conversationId);
        return ResponseEntity.noContent().build();
    }

    public record ChatRequest(String message, String conversationId) {
    }

    public record ProgressEvent(String stage, String message) {
    }

    public record ChatResponse(String conversationId, String model, boolean fallbackUsed, String message,
            List<String> columns, List<List<String>> rows, boolean usedDatabaseTools, boolean partialResults,
            String dataNotes, String followUpQuestion) {

        static ChatResponse from(ChatResult result) {
            AssistantAnswer answer = result.answer();
            return new ChatResponse(result.conversationId(), result.model(), result.fallbackUsed(), answer.answer(),
                    answer.columns(), answer.rows(), answer.usedDatabaseTools(), answer.partialResults(),
                    answer.dataNotes(), answer.followUpQuestion());
        }
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        }
        catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }

    private static String errorMessage(RuntimeException ex) {
        if (ex instanceof ResponseStatusException statusException && StringUtils.hasText(statusException.getReason())) {
            return statusException.getReason();
        }
        return "Chat request failed.";
    }
}
