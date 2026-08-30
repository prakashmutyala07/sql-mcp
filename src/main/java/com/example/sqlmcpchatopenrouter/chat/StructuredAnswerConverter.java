package com.example.sqlmcpchatopenrouter.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * Parses provider-structured JSON while tolerating occasional prose or fenced output.
 */
public class StructuredAnswerConverter implements StructuredOutputConverter<RawAssistantAnswer> {

    private static final Logger logger = LoggerFactory.getLogger(StructuredAnswerConverter.class);

    private final BeanOutputConverter<RawAssistantAnswer> delegate =
            new BeanOutputConverter<>(RawAssistantAnswer.class);

    @Override
    public RawAssistantAnswer convert(String source) {
        try {
            return this.delegate.convert(source);
        }
        catch (RuntimeException ex) {
            logger.debug("Structured-output parse failed ({}). Raw: {}", ex.getClass().getSimpleName(), source);
            String repaired = extractJsonObject(source);
            if (repaired == null) {
                throw ex;
            }
            return this.delegate.convert(repaired);
        }
    }

    @Override
    public String getFormat() {
        return "";
    }

    @Override
    public String getJsonSchema() {
        return this.delegate.getJsonSchema();
    }

    private static String extractJsonObject(String source) {
        if (source == null) {
            return null;
        }
        int start = source.indexOf('{');
        int end = source.lastIndexOf('}');
        return (start >= 0 && end > start) ? source.substring(start, end + 1) : null;
    }
}
