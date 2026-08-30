package com.example.sqlmcpchatopenrouter.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
 * map lives only on the per-request {@link Session}; it is never persisted and never logged.
 */
@Component
public class SensitiveDataGuard {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveDataGuard.class);

    private final AppProperties properties;

    private final ObjectMapper objectMapper;

    /** Lower-cased field name -> token prefix. Matching is by field name across all entities. */
    private final Map<String, String> prefixByField;

    private final byte[] secretKey;

    public SensitiveDataGuard(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        return new Session(step -> {
        });
    }

    /** @param onStep receives a short human-readable note each time a tool is about to run. */
    public Session newSession(java.util.function.Consumer<String> onStep) {
        return new Session(onStep);
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

        private Session(java.util.function.Consumer<String> onStep) {
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

        /** Restores real values in the final user-facing text. */
        public String detokenize(String text) {
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
                JsonNode root = objectMapper.readTree(payload);
                walk(root);
                return objectMapper.writeValueAsString(root);
            }
            catch (RuntimeException ex) {
                // Not JSON, or unparseable. Fail closed: never hand back something we could not inspect.
                logger.warn("Tool result was not parseable JSON; withholding it from the model. cause={}",
                        ex.getClass().getSimpleName());
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
                return null;
            }
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
            return audited(toolInput, () -> this.delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return audited(toolInput, () -> this.delegate.call(toolInput, toolContext));
        }

        private String audited(String toolInput, Supplier<String> invocation) {
            String name = getToolDefinition().name();
            this.session.toolInvocations.incrementAndGet();
            this.session.onStep.accept(ToolCallIntent.describeStep(objectMapper, name, toolInput));
            long startedAt = System.nanoTime();
            String raw;
            try {
                raw = invocation.get();
            }
            catch (RuntimeException ex) {
                SensitiveDataGuard.logger.warn("tool={} intent={} outcome=error cause={}", name,
                        ToolCallIntent.render(objectMapper, toolInput), ex.getClass().getSimpleName());
                throw ex;
            }
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            int before = this.session.tokenCount();
            String safe = this.session.tokenizeJson(raw);
            SensitiveDataGuard.logger.info("tool={} intent={} latencyMs={} resultBytes={} newTokens={}",
                    name, ToolCallIntent.render(objectMapper, toolInput),
                    latencyMs, safe == null ? 0 : safe.length(), this.session.tokenCount() - before);
            return safe;
        }
    }
}
