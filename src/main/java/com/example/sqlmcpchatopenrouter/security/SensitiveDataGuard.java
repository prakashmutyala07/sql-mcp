package com.example.sqlmcpchatopenrouter.security;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.example.sqlmcpchatopenrouter.config.AppProperties;
import com.example.sqlmcpchatopenrouter.config.SensitiveLoggingPolicy;

/**
 * Keeps sensitive column values out of everything that leaves this process.
 *
 * <p>Interception happens at the {@link ToolCallback} boundary rather than in a
 * {@code CallAdvisor}, because tool execution runs inside the model's tool-calling loop:
 * by the time an advisor sees a response the raw rows would already have been sent
 * upstream. Wrapping the callback is the last point where that can still be prevented.
 *
 * <p>Tokens are deterministic — {@code HMAC-SHA256(secret, entity.field|value)} rendered as
 * {@code CU_a3f9d2} — so the same customer reads as the same token across tool calls and
 * across turns, which is what lets the model group and join on them. The token to real-value
 * map lives only on the per-request {@link Session}; it is never persisted and is logged only
 * when local sensitive-debug logging is explicitly enabled.
 */
@Component
public class SensitiveDataGuard {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveDataGuard.class);

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])");

    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+?\\d[\\d(). -]{7,}\\d)(?!\\w)");

    private static final Pattern EXPLICIT_NAME = Pattern.compile(
            "(?i:\\b(?:customer(?:\\s+(?:named|called))?|full\\s+name\\s*(?:is|=|:)|name\\s*(?:is|=|:))\\s*['\"]?)"
                    + "([\\p{Lu}][\\p{L}'-]+(?:\\s+[\\p{Lu}][\\p{L}'-]+){1,3})");

    private static final Pattern FULL_NAME_FILTER = Pattern.compile(
            "(?i)(FullName\\s+eq\\s+['\"])([^'\"]+)(['\"])");

    private final AppProperties properties;

    private final ObjectMapper objectMapper;

    private final SensitiveLoggingPolicy sensitiveLoggingPolicy;

    /** Lower-cased field name -> token prefix. Matching is by field name across all entities. */
    private final Map<String, String> prefixByField;

    private final byte[] secretKey;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper,
            SensitiveLoggingPolicy sensitiveLoggingPolicy) {
        this.properties = properties;
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
                wrapped[i] = new GuardedToolCallback(delegates[i], this);
            }
            return wrapped;
        }

        /** Removes recognizable PII before the user message reaches memory or the model. */
        public String protectInput(String text) {
            long detectionStartedAt = System.nanoTime();
            Map<String, String> existingTokens = this.tokens.snapshot();
            int before = tokenCount();
            String protectedText = replaceMatches(text, EMAIL, "Email", prefix("email", "EM"));
            protectedText = replacePhoneMatches(protectedText, "Phone", prefix("phone", "PH"));
            protectedText = replaceGroup(protectedText, FULL_NAME_FILTER, 2, "FullName", prefix("fullname", "CU"));
            protectedText = replaceGroup(protectedText, EXPLICIT_NAME, 1, "FullName", prefix("fullname", "CU"));
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
                        sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? text : "<raw input hidden>",
                        sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? detectedEntityValues(existingTokens)
                                : "entities=" + detected + " tokenTypes=" + this.tokens.prefixes(),
                        sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? inputTokenMap(existingTokens)
                                : "raw values hidden; protected tokens=" + newlyCreatedTokens(existingTokens),
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
            return redactPhoneMatches(EMAIL.matcher(protectedText).replaceAll("[REDACTED_EMAIL]"));
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

        /** Tokenizes every sensitive field in a JSON tool result. Non-JSON payloads pass through. */
        String tokenizeJson(String payload) {
            if (!StringUtils.hasText(payload)) {
                return payload;
            }
            try {
                long startedAt = System.nanoTime();
                Map<String, String> existingTokens = this.tokens.snapshot();
                int before = tokenCount();
                JsonNode root = objectMapper.readTree(payload);
                walk(root);
                String protectedPayload = objectMapper.writeValueAsString(root);
                int protectedEntities = tokenCount() - before;
                logger.info("[STEP 7 - RESULT PROTECTION] requestId={} protected={} sensitiveValues={} "
                        + "tokenTypes={} durationMs={}", this.requestId, protectedEntities > 0,
                        protectedEntities, this.tokens.prefixes(), elapsedMillis(startedAt));
                if (logger.isDebugEnabled()) {
                    logger.debug("""
                            ------------------------------------------------------------
                            [STEP 7 - RESULT PROTECTION] requestId={}

                            INPUT
                            {}

                            DETECTED
                            {}

                            OUTPUT TO MODEL
                            {}
                            ------------------------------------------------------------""", this.requestId,
                            sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? payload : "<raw database result hidden>",
                            sensitiveLoggingPolicy.sensitiveLoggingEnabled() ? detectedEntityValues(existingTokens)
                                    : "sensitiveValues=" + protectedEntities + " tokenTypes=" + this.tokens.prefixes(),
                            protectedPayload);
                }
                return protectedPayload;
            }
            catch (RuntimeException ex) {
                // Not JSON, or unparseable. Fail closed: never hand back something we could not inspect.
                logger.warn("[STEP 7 - RESULT PROTECTION] requestId={} parseable=false action=withheld errorType={}",
                        this.requestId, ex.getClass().getSimpleName());
                return "{\"error\":\"Tool result could not be inspected for sensitive data and was withheld.\"}";
            }
        }

        private void walk(JsonNode node) {
            if (node instanceof ObjectNode object) {
                List<String> names = new ArrayList<>(object.propertyNames());
                for (String name : names) {
                    JsonNode child = object.get(name);
                    if (child == null) {
                        continue;
                    }
                    String prefix = prefixByField.get(name.toLowerCase());
                    if (prefix != null && child.isString() && StringUtils.hasText(child.stringValue())) {
                        object.put(name, this.tokens.tokenFor(name, prefix, child.stringValue()));
                    }
                    else if (child.isString()) {
                        // MCP wraps the real payload as JSON *inside* a string (content[0].text),
                        // so the rows are invisible to a plain tree walk. Descend into it.
                        String nested = tokenizeEmbedded(child.stringValue());
                        if (nested != null) {
                            object.put(name, nested);
                        }
                    }
                    else {
                        walk(child);
                    }
                }
            }
            else if (node instanceof ArrayNode array) {
                array.forEach(this::walk);
            }
        }

        /** Tokenizes a JSON document that arrived encoded inside a string value. */
        private String tokenizeEmbedded(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.strip();
            if (trimmed.length() < 2 || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
                return null;
            }
            try {
                JsonNode nested = objectMapper.readTree(trimmed);
                walk(nested);
                return objectMapper.writeValueAsString(nested);
            }
            catch (RuntimeException ex) {
                return "{\"error\":\"Embedded tool result could not be inspected for sensitive data and was withheld.\"}";
            }
        }

        private String replaceMatches(String text, Pattern pattern, String field, String prefix) {
            if (!StringUtils.hasText(text)) {
                return text;
            }
            Matcher matcher = pattern.matcher(text);
            StringBuilder safe = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(safe,
                        Matcher.quoteReplacement(this.tokens.tokenFor(field, prefix, matcher.group())));
            }
            matcher.appendTail(safe);
            return safe.toString();
        }

        private String replacePhoneMatches(String text, String field, String prefix) {
            if (!StringUtils.hasText(text)) {
                return text;
            }
            Matcher matcher = PHONE.matcher(text);
            StringBuilder safe = new StringBuilder();
            while (matcher.find()) {
                String candidate = matcher.group();
                String replacement = candidate.chars().filter(Character::isDigit).count() >= 10
                        ? this.tokens.tokenFor(field, prefix, candidate) : candidate;
                matcher.appendReplacement(safe, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(safe);
            return safe.toString();
        }

        private String redactPhoneMatches(String text) {
            Matcher matcher = PHONE.matcher(text);
            StringBuilder safe = new StringBuilder();
            while (matcher.find()) {
                String candidate = matcher.group();
                String replacement = candidate.chars().filter(Character::isDigit).count() >= 10
                        ? "[REDACTED_PHONE]" : candidate;
                matcher.appendReplacement(safe, replacement);
            }
            matcher.appendTail(safe);
            return safe.toString();
        }

        private String replaceGroup(String text, Pattern pattern, int group, String field, String prefix) {
            if (!StringUtils.hasText(text)) {
                return text;
            }
            Matcher matcher = pattern.matcher(text);
            StringBuilder safe = new StringBuilder();
            int copiedUntil = 0;
            while (matcher.find()) {
                safe.append(text, copiedUntil, matcher.start(group));
                safe.append(this.tokens.tokenFor(field, prefix, matcher.group(group)));
                copiedUntil = matcher.end(group);
            }
            return copiedUntil == 0 ? text : safe.append(text, copiedUntil, text.length()).toString();
        }

        private String prefix(String field, String fallback) {
            return prefixByField.getOrDefault(field, fallback);
        }

        private Map<String, String> newTokenSnapshot(Map<String, String> existingTokens) {
            return this.tokens.snapshot().entrySet().stream()
                    .filter(entry -> !existingTokens.containsKey(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (first, second) -> first, LinkedHashMap::new));
        }

        private String detectedEntityValues(Map<String, String> existingTokens) {
            Map<String, String> values = newTokenSnapshot(existingTokens);
            return values.isEmpty() ? "none" : values.entrySet().stream()
                    .map(entry -> entry.getKey().substring(0, entry.getKey().indexOf('_'))
                            + " = " + entry.getValue())
                    .collect(Collectors.joining("\n"));
        }

        private String inputTokenMap(Map<String, String> existingTokens) {
            Map<String, String> values = newTokenSnapshot(existingTokens);
            return values.isEmpty() ? "none" : values.entrySet().stream()
                    .map(entry -> entry.getValue() + " -> " + entry.getKey())
                    .collect(Collectors.joining("\n"));
        }

        private List<String> newlyCreatedTokens(Map<String, String> existingTokens) {
            return new ArrayList<>(newTokenSnapshot(existingTokens).keySet());
        }

    }

    /**
     * Tokenizes the delegate's output and records tool-call intent plus latency.
     * Argument literals are masked in logs, so a filter like {@code FullName eq 'Jane Doe'}
     * is recorded as {@code FullName eq '?'}.
     */
    private final class GuardedToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private final Session session;

        private GuardedToolCallback(ToolCallback delegate, Session session) {
            this.delegate = delegate;
            this.session = session;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return this.delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return this.delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return audited(toolInput, detokenizedToolInput -> this.delegate.call(detokenizedToolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return audited(toolInput, detokenizedToolInput -> this.delegate.call(detokenizedToolInput, toolContext));
        }

        private String audited(String toolInput, ToolInvocation invocation) {
            String name = getToolDefinition().name();
            this.session.toolInvocations.incrementAndGet();
            this.session.onStep.accept(ToolCallIntent.describeStep(objectMapper, name, toolInput));
            String detokenizedToolInput = this.session.tokens.detokenize(toolInput);
            int resolvedTokens = ToolCallIntent.resolvedTokenCount(toolInput, detokenizedToolInput);
            SensitiveDataGuard.logger.info("[STEP 5 - MCP TOOL] requestId={} tool={} approved=true "
                    + "resolvedTokens={}", this.session.requestId, name, resolvedTokens);
            if (SensitiveDataGuard.logger.isDebugEnabled()) {
                SensitiveDataGuard.logger.debug("""
                        ------------------------------------------------------------
                        [STEP 5 - MCP TOOL] requestId={}

                        INPUT FROM MODEL
                        Tool:
                        {}

                        Arguments:
                        {}

                        TOKEN RESOLUTION
                        {}

                        OUTPUT TO DAB
                        {}
                        ------------------------------------------------------------""", this.session.requestId, name, toolInput,
                        sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? tokenResolutionMap(toolInput) : "resolvedTokens=" + resolvedTokens,
                        sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? detokenizedToolInput : protectedDabBoundaryView(toolInput));
            }
            long startedAt = System.nanoTime();
            String raw;
            try {
                if (SensitiveDataGuard.logger.isDebugEnabled()) {
                    SensitiveDataGuard.logger.debug("""
                            ------------------------------------------------------------
                            [STEP 6 - DAB / SQL] requestId={}

                            INPUT TO DAB

                            Tool:
                            {}

                            Entity:
                            {}

                            Arguments:
                            {}

                            SQL Executed:
                            Actual SQL is generated/executed inside Microsoft DAB and is not available at the Spring application logging boundary.
                            ------------------------------------------------------------""", this.session.requestId, name,
                            ToolCallIntent.entityName(objectMapper, detokenizedToolInput),
                            sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                    ? detokenizedToolInput : protectedDabBoundaryView(toolInput));
                }
                raw = invocation.call(detokenizedToolInput);
            }
            catch (RuntimeException ex) {
                SensitiveDataGuard.logger.error("[STEP 6 - DAB / SQL] requestId={} tool={} failed errorType={}",
                        this.session.requestId, name, ex.getClass().getSimpleName());
                throw ex;
            }
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            int rows = resultRowCount(raw);
            SensitiveDataGuard.logger.info("[STEP 6 - DAB / SQL] requestId={} tool={} entity={} rows={} "
                    + "durationMs={}", this.session.requestId, name,
                    ToolCallIntent.entityName(objectMapper, detokenizedToolInput), rowsDescription(rows), latencyMs);
            if (SensitiveDataGuard.logger.isDebugEnabled()) {
                SensitiveDataGuard.logger.debug("""
                        ------------------------------------------------------------
                        [STEP 6 - DAB / SQL] requestId={}

                        OUTPUT FROM DAB / DATABASE

                        rows={}
                        durationMs={}

                        {}
                        ------------------------------------------------------------""", this.session.requestId, rowsDescription(rows),
                        latencyMs, sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? raw : "<raw database result hidden until Step 7 protected output>");
            }
            int before = this.session.tokenCount();
            String safe = this.session.tokenizeJson(raw);
            return safe;
        }

        private String tokenResolutionMap(String toolInput) {
            Map<String, String> matches = this.session.tokens.snapshot().entrySet().stream()
                    .filter(entry -> toolInput != null && toolInput.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (first, second) -> first, LinkedHashMap::new));
            return matches.isEmpty() ? "none" : matches.entrySet().stream()
                    .map(entry -> entry.getKey() + " -> " + entry.getValue())
                    .collect(Collectors.joining("\n"));
        }

        private String protectedDabBoundaryView(String protectedToolInput) {
            return protectedToolInput == null ? "" : protectedToolInput;
        }
    }

    @FunctionalInterface
    private interface ToolInvocation {

        String call(String detokenizedToolInput);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private int resultRowCount(String payload) {
        if (!StringUtils.hasText(payload)) {
            return -1;
        }
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            int direct = countValueArray(root);
            if (direct >= 0) {
                return direct;
            }
            JsonNode content = root.get("content");
            if (content instanceof ArrayNode array && !array.isEmpty()) {
                for (JsonNode item : array) {
                    JsonNode text = item.get("text");
                    if (text != null && text.isString()) {
                        JsonNode nested = this.objectMapper.readTree(text.stringValue());
                        int nestedCount = countValueArray(nested);
                        if (nestedCount >= 0) {
                            return nestedCount;
                        }
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            return -1;
        }
        return -1;
    }

    private static int countValueArray(JsonNode node) {
        if (node == null) {
            return -1;
        }
        JsonNode value = node.get("value");
        if (value instanceof ArrayNode array) {
            return array.size();
        }
        JsonNode result = node.get("result");
        if (result != null) {
            JsonNode resultValue = result.get("value");
            if (resultValue instanceof ArrayNode array) {
                return array.size();
            }
        }
        return -1;
    }

    private static String rowsDescription(int rows) {
        return rows >= 0 ? Integer.toString(rows) : "unknown";
    }
}
