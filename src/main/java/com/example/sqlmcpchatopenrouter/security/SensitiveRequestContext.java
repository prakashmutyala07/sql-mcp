package com.example.sqlmcpchatopenrouter.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.SensitiveLoggingPolicy;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-request sensitive-data state. One instance per chat turn; discarded when the turn ends.
 */
public final class SensitiveRequestContext {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveDataGuard.class);

    private final SensitiveTokenStore tokens;

    private final java.util.concurrent.atomic.AtomicInteger toolInvocations =
            new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.function.Consumer<String> onStep;

    private final String requestId;

    private final ObjectMapper objectMapper;

    private final SensitiveLoggingPolicy sensitiveLoggingPolicy;

    private final Map<String, String> prefixByField;

    private final PiiDetector piiDetector;

    private final SensitivePayloadProtector payloadProtector;

    SensitiveRequestContext(String requestId, java.util.function.Consumer<String> onStep, byte[] secretKey,
            ObjectMapper objectMapper, SensitiveLoggingPolicy sensitiveLoggingPolicy, Map<String, String> prefixByField,
            PiiDetector piiDetector, SensitivePayloadProtector payloadProtector) {
        this.requestId = requestId;
        this.onStep = onStep;
        this.tokens = new SensitiveTokenStore(secretKey);
        this.objectMapper = objectMapper;
        this.sensitiveLoggingPolicy = sensitiveLoggingPolicy;
        this.prefixByField = prefixByField;
        this.piiDetector = piiDetector;
        this.payloadProtector = payloadProtector;
    }

    /** Decorates each MCP tool so its result is tokenized and audited before the model sees it. */
    public ToolCallback[] wrap(ToolCallback[] delegates) {
        ToolCallback[] wrapped = new ToolCallback[delegates.length];
        for (int i = 0; i < delegates.length; i++) {
            wrapped[i] = new SecureMcpToolCallback(delegates[i], this, this.objectMapper,
                    this.payloadProtector, this.sensitiveLoggingPolicy);
        }
        return wrapped;
    }

    /** Removes recognizable PII before the user message reaches memory or the model. */
    public String protectInput(String text) {
        long detectionStartedAt = System.nanoTime();
        Map<String, String> existingTokens = this.tokens.snapshot();
        int before = tokenCount();
        String protectedText = this.piiDetector.protect(text, this.tokens, this.prefixByField);
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
                    this.sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? text : "<raw input hidden>",
                    this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                            ? detectedEntityValues(this.tokens, existingTokens)
                            : "entities=" + detected + " tokenTypes=" + this.tokens.prefixes(),
                    this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
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
        return this.piiDetector.redactProviderContactDetails(protectedText);
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
