package com.example.sqlmcpchatopenrouter.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Models models, Execution execution, Memory memory, Openrouter openrouter,
        Security security, List<SensitiveField> sensitiveFields) {

    public AppProperties {
        sensitiveFields = sensitiveFields == null ? List.of() : List.copyOf(sensitiveFields);
    }

    public record Models(String primary, String fallback) {
    }

    public record Execution(boolean fallbackEnabled, boolean primaryRetryEnabled, Integer maxCompletionTokens,
            Double temperature, java.time.Duration requestTimeout, ResponseFormat responseFormat) {

        public Execution {
            requestTimeout = requestTimeout == null ? java.time.Duration.ofSeconds(120) : requestTimeout;
            responseFormat = responseFormat == null ? ResponseFormat.JSON_SCHEMA : responseFormat;
        }
    }

    public enum ResponseFormat {

        JSON_SCHEMA,

        PROMPT_JSON
    }

    public record Memory(int maxMessages) {
    }

    public record Openrouter(String referer, String title) {
    }

    public record Security(String tokenSecretKey) {
    }

    /**
     * One sensitive {@code entity.field} pair. {@code prefix} is the human-readable
     * marker on the emitted token, e.g. {@code CU_a3f9d2}.
     */
    public record SensitiveField(String entity, String field, String prefix) {

        public String prefixOrDefault() {
            return (this.prefix == null || this.prefix.isBlank())
                    ? this.entity.substring(0, Math.min(2, this.entity.length())).toUpperCase()
                    : this.prefix;
        }
    }
}
