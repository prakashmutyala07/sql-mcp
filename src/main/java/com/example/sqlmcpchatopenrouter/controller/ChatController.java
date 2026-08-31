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

import com.example.sqlmcpchatopenrouter.chat.ChatOperations;
import com.example.sqlmcpchatopenrouter.chat.ChatResponse;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.mcp.McpToolOperations;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final ChatOperations chatCoordinator;

    private final McpToolOperations mcpToolCatalog;

    private final AsyncTaskExecutor chatTaskExecutor;

    public ChatController(ChatOperations chatCoordinator, McpToolOperations mcpToolCatalog,
            AsyncTaskExecutor chatTaskExecutor) {
        this.chatCoordinator = chatCoordinator;
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

        return ResponseEntity.ok(this.chatCoordinator.chat(request.message(), request.conversationId()));
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
                ChatResponse result = this.chatCoordinator.chat(request.message(), request.conversationId(),
                        (stage, message) -> send(emitter, "progress", new ProgressEvent(stage, message)));
                send(emitter, "complete", result);
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
        this.chatCoordinator.clearMemory(conversationId);
        return ResponseEntity.noContent().build();
    }

    public record ChatRequest(String message, String conversationId) {
    }

    public record ProgressEvent(String stage, String message) {
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
