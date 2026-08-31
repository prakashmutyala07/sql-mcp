package com.example.sqlmcpchatopenrouter.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

class ChatResponseTests {

    @Test
    void springAiConverterParsesTypedEmptyResponse() {
        BeanOutputConverter<ChatResponse.ModelAnswer> converter =
                new BeanOutputConverter<>(ChatResponse.ModelAnswer.class);

        ChatResponse.ModelAnswer answer = converter.convert("""
                {
                  "status": "EMPTY",
                  "answer": "No matching orders were found.",
                  "columns": [],
                  "rows": [],
                  "partialResults": false,
                  "dataNotes": "Calendar month scope.",
                  "followUpQuestion": ""
                }
                """);
        ChatResponse response = ChatResponse.from("demo", "model", false, answer, true);

        assertThat(response.status()).isEqualTo(ChatResponse.Status.EMPTY);
        assertThat(response.message()).isEqualTo("No matching orders were found.");
        assertThat(response.columns()).isEmpty();
        assertThat(response.usedDatabaseTools()).isTrue();
    }
}
