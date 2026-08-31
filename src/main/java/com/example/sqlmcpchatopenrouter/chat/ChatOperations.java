package com.example.sqlmcpchatopenrouter.chat;

public interface ChatOperations {

    ChatResponse chat(String message, String conversationId);

    ChatResponse chat(String message, String conversationId, ProgressSink progressSink);

    void clearMemory(String conversationId);
}
