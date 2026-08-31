package com.example.sqlmcpchatopenrouter.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.config.SensitiveLoggingPolicy;

import tools.jackson.databind.ObjectMapper;

/**
 * Public facade for request-local sensitive-data protection.
 *
 * <p>Interception happens at the {@link ToolCallback} boundary rather than in a
 * {@code CallAdvisor}, because tool execution runs inside the model's tool-calling loop:
 * by the time an advisor sees a response the raw rows would already have been sent
 * upstream. Wrapping the callback is the last point where that can still be prevented.
 */
@Component
public class SensitiveDataGuard {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveDataGuard.class);

    private final ObjectMapper objectMapper;

    private final SensitiveLoggingPolicy sensitiveLoggingPolicy;

    /** Lower-cased field name -> token prefix. Matching is by field name across all entities. */
    private final Map<String, String> prefixByField;

    private final byte[] secretKey;

    private final PiiDetector piiDetector = new PiiDetector();

    private final SensitivePayloadProtector payloadProtector;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            SensitiveLoggingPolicy sensitiveLoggingPolicy) {
        this.objectMapper = objectMapper;
        this.sensitiveLoggingPolicy = sensitiveLoggingPolicy;
        this.prefixByField = properties.sensitiveFields().stream()
                .collect(Collectors.toMap(field -> field.field().toLowerCase(),
                        AppProperties.SensitiveField::prefixOrDefault, (first, second) -> first));
        String configured = properties.security().tokenSecretKey();
        if (!StringUtils.hasText(configured) && !this.prefixByField.isEmpty()) {
            throw new IllegalStateException(
                    "TOKEN_SECRET_KEY must be set when app.sensitive-fields is non-empty.");
        }
        this.secretKey = StringUtils.hasText(configured)
                ? configured.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.payloadProtector = new SensitivePayloadProtector(objectMapper, this.prefixByField,
                sensitiveLoggingPolicy);
        logger.info("Sensitive-field redaction active for {} field(s): {}", this.prefixByField.size(),
                this.prefixByField.keySet());
    }

    public Session newSession() {
        return new Session("none", step -> {
        });
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public Session newSession(java.util.function.Consumer<String> onStep) {
        return new Session("none", onStep);
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public Session newSession(String requestId, java.util.function.Consumer<String> onStep) {
        return new Session(StringUtils.hasText(requestId) ? requestId : "none", onStep);
    }

    public Set<String> protectedFields() {
        return this.prefixByField.keySet();
    }

    /**
     * Per-request tokenization state. One instance per chat turn; discarded when the turn ends.
     */
    public final class Session {

        private final SensitiveTokenStore tokens = new SensitiveTokenStore(SensitiveDataGuard.this.secretKey);

        private final java.util.concurrent.atomic.AtomicInteger toolInvocations =
                new java.util.concurrent.atomic.AtomicInteger();

        private final java.util.function.Consumer<String> onStep;

        private final String requestId;

        private Session(String requestId, java.util.function.Consumer<String> onStep) {
            this.requestId = requestId;
            this.onStep = onStep;
        }

        /** Decorates each MCP tool so its result is tokenized and audited before the model sees it. */
        public ToolCallback[] wrap(ToolCallback[] delegates) {
            ToolCallback[] wrapped = new ToolCallback[delegates.length];
            for (int i = 0; i < delegates.length; i++) {
                wrapped[i] = new SecureMcpToolCallback(delegates[i], this,
                        SensitiveDataGuard.this.objectMapper, SensitiveDataGuard.this.payloadProtector,
                        SensitiveDataGuard.this.sensitiveLoggingPolicy);
            }
            return wrapped;
        }

        /** Removes recognizable PII before the user message reaches memory or the model. */
        public String protectInput(String text) {
            long detectionStartedAt = System.nanoTime();
            Map<String, String> existingTokens = this.tokens.snapshot();
            int before = tokenCount();
            String protectedText = SensitiveDataGuard.this.piiDetector.protect(text, this.tokens,
                    SensitiveDataGuard.this.prefixByField);
            int detected = tokenCount() - before;
            logger.info("[STEP 3 - PII PROTECTION] requestId={} protected={} entities={} tokenTypes={} "
                    + "durationMs={}", this.requestId, detected > 0, detected, this.tokens.prefixes(),
                    elapsedMillis(detectionStartedAt));
            if (logger.isDebugEnabled()) {
                logger.debug("""
                        ------------------------------------------------------------
                        [STEP 3 - PII PROTECTION] requestId={}

                        INPUT
                        {}

                        DETECTED
                        {}

                        TOKEN MAP
                        {}

                        OUTPUT
                        {}
                        ------------------------------------------------------------""", this.requestId,
                        SensitiveDataGuard.this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? text : "<raw input hidden>",
                        SensitiveDataGuard.this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? detectedEntityValues(this.tokens, existingTokens)
                                : "entities=" + detected + " tokenTypes=" + this.tokens.prefixes(),
                        SensitiveDataGuard.this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? inputTokenMap(this.tokens, existingTokens)
                                : "raw values hidden; protected tokens=" + newlyCreatedTokens(this.tokens, existingTokens),
                        protectedText);
            }
            return protectedText;
        }

        /** Final defense for provider-generated email/phone text. Database tokens stay pseudonymized. */
        public String protectOutput(String text) {
            if (!StringUtils.hasText(text)) {
                return text;
            }
            String protectedText = this.tokens.protectKnownValues(text);
            return SensitiveDataGuard.this.piiDetector.redactProviderContactDetails(protectedText);
        }

        /** Restores request-local tokens only after model calls and sanitized memory writes are complete. */
        public String revealForTrustedLocalDisplay(String text) {
            return this.tokens.detokenize(text);
        }

        public int tokenCount() {
            return this.tokens.size();
        }

        /** How many tool calls actually executed this turn. Ground truth for usedDatabaseTools. */
        public int toolInvocations() {
            return this.toolInvocations.get();
        }

        SensitiveTokenStore tokens() {
            return this.tokens;
        }

        Map<String, String> tokenSnapshot() {
            return this.tokens.snapshot();
        }

        String detokenize(String text) {
            return this.tokens.detokenize(text);
        }

        void recordToolInvocation() {
            this.toolInvocations.incrementAndGet();
        }

        void onStep(String step) {
            this.onStep.accept(step);
        }

        String requestId() {
            return this.requestId;
        }
    }

    static String detectedEntityValues(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        Map<String, String> values = newTokenSnapshot(tokens, existingTokens);
        return values.isEmpty() ? "none" : values.entrySet().stream()
                .map(entry -> entry.getKey().substring(0, entry.getKey().indexOf('_'))
                        + " = " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    private static String inputTokenMap(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        Map<String, String> values = newTokenSnapshot(tokens, existingTokens);
        return values.isEmpty() ? "none" : values.entrySet().stream()
                .map(entry -> entry.getValue() + " -> " + entry.getKey())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> newlyCreatedTokens(SensitiveTokenStore tokens, Map<String, String> existingTokens) {
        return new ArrayList<>(newTokenSnapshot(tokens, existingTokens).keySet());
    }

    private static Map<String, String> newTokenSnapshot(SensitiveTokenStore tokens,
            Map<String, String> existingTokens) {
        return tokens.snapshot().entrySet().stream()
                .filter(entry -> !existingTokens.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, LinkedHashMap::new));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
