package com.example.sqlmcpchatopenrouter.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;

/**
 * Stable answer shape returned to controllers/UI after normalizing flexible model JSON.
 */
public record AssistantAnswer(String answer, List<String> columns, List<List<String>> rows,
        Boolean usedDatabaseTools, Boolean partialResults, String dataNotes, String followUpQuestion) {

    public AssistantAnswer {
        answer = safeString(answer);
        columns = (columns == null) ? List.of() : List.copyOf(columns);
        rows = (rows == null) ? List.of()
                : rows.stream().map(row -> (row == null) ? List.<String>of() : List.copyOf(row)).toList();
        usedDatabaseTools = usedDatabaseTools != null && usedDatabaseTools;
        partialResults = partialResults != null && partialResults;
        dataNotes = safeString(dataNotes);
        followUpQuestion = safeString(followUpQuestion);
    }

    public static AssistantAnswer from(RawAssistantAnswer raw) {
        if (raw == null) {
            return new AssistantAnswer("", List.of(), List.of(), false, false, "", "");
        }
        List<String> columns = normalizeColumns(raw.columns());
        return new AssistantAnswer(raw.answer(), columns, normalizeRows(raw.rows(), columns),
                raw.usedDatabaseTools(), raw.partialResults(), raw.dataNotes(), raw.followUpQuestion());
    }

    private static List<String> normalizeColumns(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(AssistantAnswer::safeString).filter(StringUtils::hasText).toList();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return List.of(text);
        }
        return List.of();
    }

    private static List<List<String>> normalizeRows(Object value, List<String> columns) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<List<String>> normalized = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof List<?> cells) {
                normalized.add(cells.stream().map(AssistantAnswer::safeString).toList());
            }
            else if (row instanceof Map<?, ?> map && !columns.isEmpty()) {
                normalized.add(columns.stream().map(column -> safeString(map.get(column))).toList());
            }
            else if (row != null) {
                normalized.add(List.of(safeString(row)));
            }
        }
        return List.copyOf(normalized);
    }

    private static String safeString(Object value) {
        return (value == null) ? "" : value.toString();
    }
}
