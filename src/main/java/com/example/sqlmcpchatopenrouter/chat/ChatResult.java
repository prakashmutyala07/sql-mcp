package com.example.sqlmcpchatopenrouter.chat;

public record ChatResult(String conversationId, String model, boolean fallbackUsed, AssistantAnswer answer) {
}
