package com.example.sqlmcpchatopenrouter.chat;

/**
 * Flexible shape for model output. Models sometimes emit row objects or scalar values
 * even when asked for string arrays, so this is normalized before leaving the chat layer.
 */
public record RawAssistantAnswer(String answer, Object columns, Object rows,
        Boolean usedDatabaseTools, Boolean partialResults, String dataNotes, String followUpQuestion) {

    public RawAssistantAnswer {
        answer = safeString(answer);
        dataNotes = safeString(dataNotes);
        followUpQuestion = safeString(followUpQuestion);
        usedDatabaseTools = usedDatabaseTools != null && usedDatabaseTools;
        partialResults = partialResults != null && partialResults;
    }

    private static String safeString(Object value) {
        return (value == null) ? "" : value.toString();
    }
}
