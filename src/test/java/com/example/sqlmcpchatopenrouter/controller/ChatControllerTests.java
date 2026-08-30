package com.example.sqlmcpchatopenrouter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.sqlmcpchatopenrouter.chat.AiChatOperations;
import com.example.sqlmcpchatopenrouter.chat.AssistantAnswer;
import com.example.sqlmcpchatopenrouter.chat.ChatResult;
import com.example.sqlmcpchatopenrouter.chat.ProgressSink;
import com.example.sqlmcpchatopenrouter.mcp.McpToolCatalog;
import com.example.sqlmcpchatopenrouter.mcp.McpToolOperations;

class ChatControllerTests {

    private final FakeAiChatOperations aiChatService = new FakeAiChatOperations();

    private final FakeMcpToolOperations mcpToolCatalog = new FakeMcpToolOperations();

    private final ChatController controller = new ChatController(this.aiChatService, this.mcpToolCatalog,
            new SameThreadAsyncTaskExecutor());

    @Test
    void chatRequiresMessage() {
        ChatController.ChatRequest request = new ChatController.ChatRequest(" ", "demo");

        assertThatThrownBy(() -> this.controller.chat(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void chatMapsStructuredServiceResponse() {
        AssistantAnswer answer = new AssistantAnswer("There are 4 entities.",
                List.of("Entity"), List.of(List.of("Customer"), List.of("Order")), true, false, "metadata only", "");
        this.aiChatService.chatResult = new ChatResult("demo", "minimax/minimax-m3:free", false, answer);

        ChatController.ChatResponse response = this.controller
                .chat(new ChatController.ChatRequest("List entities", "demo"))
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.conversationId()).isEqualTo("demo");
        assertThat(response.model()).isEqualTo("minimax/minimax-m3:free");
        assertThat(response.columns()).containsExactly("Entity");
        assertThat(response.rows()).hasSize(2);
        assertThat(response.message()).isEqualTo("There are 4 entities.");
        assertThat(response.usedDatabaseTools()).isTrue();
        assertThat(response.partialResults()).isFalse();
    }

    @Test
    void mcpToolsDelegatesToCatalog() {
        List<McpToolCatalog.ToolSummary> tools = List.of(new McpToolCatalog.ToolSummary("describe_entities",
                "Lists all entities", "{}"));
        this.mcpToolCatalog.tools = tools;

        assertThat(this.controller.mcpTools()).isEqualTo(tools);
    }

    @Test
    void clearMemoryDelegatesToService() {
        this.controller.clearMemory("demo");

        assertThat(this.aiChatService.clearedConversations).containsExactly("demo");
    }

    static class SameThreadAsyncTaskExecutor implements AsyncTaskExecutor {

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    static class FakeAiChatOperations implements AiChatOperations {

        private ChatResult chatResult = new ChatResult("demo", "model", false,
                new AssistantAnswer("", List.of(), List.of(), false, false, "", ""));

        private final List<String> clearedConversations = new ArrayList<>();

        @Override
        public ChatResult chat(String message, String conversationId) {
            return this.chatResult;
        }

        @Override
        public ChatResult chat(String message, String conversationId, ProgressSink progressSink) {
            return this.chatResult;
        }

        @Override
        public void clearMemory(String conversationId) {
            this.clearedConversations.add(conversationId);
        }
    }

    static class FakeMcpToolOperations implements McpToolOperations {

        private List<McpToolCatalog.ToolSummary> tools = List.of();

        @Override
        public List<McpToolCatalog.ToolSummary> tools() {
            return this.tools;
        }
    }
}
