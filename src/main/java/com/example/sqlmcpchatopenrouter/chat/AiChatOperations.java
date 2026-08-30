package com.example.sqlmcpchatopenrouter.chat;

public interface AiChatOperations {

    ChatResult chat(String message, String conversationId);

    ChatResult chat(String message, String conversationId, ProgressSink progressSink);

    void clearMemory(String conversationId);
}
