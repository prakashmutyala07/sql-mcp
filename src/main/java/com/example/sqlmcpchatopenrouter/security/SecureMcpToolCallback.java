package com.example.sqlmcpchatopenrouter.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.SensitiveLoggingPolicy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Executes MCP tools through the local sensitive-data boundary.
 */
final class SecureMcpToolCallback implements ToolCallback {

    private static final Logger logger = LoggerFactory.getLogger(SecureMcpToolCallback.class);

    private final ToolCallback delegate;

    private final SensitiveDataGuard.Session session;

    private final ObjectMapper objectMapper;

    private final SensitivePayloadProtector payloadProtector;

    private final SensitiveLoggingPolicy sensitiveLoggingPolicy;

    SecureMcpToolCallback(ToolCallback delegate, SensitiveDataGuard.Session session, ObjectMapper objectMapper,
            SensitivePayloadProtector payloadProtector, SensitiveLoggingPolicy sensitiveLoggingPolicy) {
        this.delegate = delegate;
        this.session = session;
        this.objectMapper = objectMapper;
        this.payloadProtector = payloadProtector;
        this.sensitiveLoggingPolicy = sensitiveLoggingPolicy;
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
        this.session.recordToolInvocation();
        this.session.onStep(ToolCallIntent.describeStep(this.objectMapper, name, toolInput));
        String detokenizedToolInput = this.session.detokenize(toolInput);
        int resolvedTokens = SensitiveTokenStore.resolvedTokenCount(toolInput, detokenizedToolInput);
        logger.info("[STEP 5 - MCP TOOL] requestId={} tool={} approved=true resolvedTokens={}",
                this.session.requestId(), name, resolvedTokens);
        if (logger.isDebugEnabled()) {
            logger.debug("""
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
                    ------------------------------------------------------------""", this.session.requestId(), name, toolInput,
                    this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                            ? tokenResolutionMap(toolInput) : "resolvedTokens=" + resolvedTokens,
                    this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                            ? detokenizedToolInput : protectedDabBoundaryView(toolInput));
        }
        long startedAt = System.nanoTime();
        String raw;
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("""
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
                        ------------------------------------------------------------""", this.session.requestId(), name,
                        ToolCallIntent.entityName(this.objectMapper, detokenizedToolInput),
                        this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                                ? detokenizedToolInput : protectedDabBoundaryView(toolInput));
            }
            raw = invocation.call(detokenizedToolInput);
        }
        catch (RuntimeException ex) {
            logger.error("[STEP 6 - DAB / SQL] requestId={} tool={} failed errorType={}",
                    this.session.requestId(), name, ex.getClass().getSimpleName());
            throw ex;
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        int rows = resultRowCount(raw);
        logger.info("[STEP 6 - DAB / SQL] requestId={} tool={} entity={} rows={} durationMs={}",
                this.session.requestId(), name, ToolCallIntent.entityName(this.objectMapper, detokenizedToolInput),
                rowsDescription(rows), latencyMs);
        if (logger.isDebugEnabled()) {
            logger.debug("""
                    ------------------------------------------------------------
                    [STEP 6 - DAB / SQL] requestId={}

                    OUTPUT FROM DAB / DATABASE

                    rows={}
                    durationMs={}

                    {}
                    ------------------------------------------------------------""", this.session.requestId(), rowsDescription(rows),
                    latencyMs, this.sensitiveLoggingPolicy.sensitiveLoggingEnabled()
                            ? raw : "<raw database result hidden until Step 7 protected output>");
        }
        return this.payloadProtector.protect(raw, this.session.tokens(), this.session.requestId());
    }

    private String tokenResolutionMap(String toolInput) {
        Map<String, String> matches = this.session.tokenSnapshot().entrySet().stream()
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

    @FunctionalInterface
    private interface ToolInvocation {

        String call(String detokenizedToolInput);
    }
}
