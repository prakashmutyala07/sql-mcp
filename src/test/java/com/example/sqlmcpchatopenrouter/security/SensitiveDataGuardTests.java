package com.example.sqlmcpchatopenrouter.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.sqlmcpchatopenrouter.config.AppProperties;

import tools.jackson.databind.json.JsonMapper;

class SensitiveDataGuardTests {

    private static final String REAL_NAME = "Jane Doe";

    private static final String REAL_EMAIL = "jane.doe@example.com";

    private static final String TOOL_RESULT = """
            {"value":[
              {"CustomerId":1,"FullName":"Jane Doe","Email":"jane.doe@example.com","City":"Austin"},
              {"CustomerId":2,"FullName":"Sam Patel","Email":"sam.patel@example.com","City":"Austin"}
            ]}
            """;

    private final SensitiveDataGuard guard = new SensitiveDataGuard(properties(), JsonMapper.builder().build());

    private static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, 1200, 0.1, java.time.Duration.ofSeconds(120)),
                new AppProperties.Memory(20),
                new AppProperties.Openrouter("", "title"),
                new AppProperties.Security("unit-test-secret"),
                List.of(new AppProperties.SensitiveField("Customer", "FullName", "CU"),
                        new AppProperties.SensitiveField("Customer", "Email", "EM")));
    }

    private static ToolCallback stubCallback(String payload) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records")
                        .description("read records").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return payload;
            }
        };
    }

    @Test
    void sensitiveValuesNeverReachTheModelPayload() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload)
                .as("raw sensitive values must never appear in what is sent to the LLM")
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL)
                .doesNotContain("Sam Patel")
                .doesNotContain("sam.patel@example.com");
        assertThat(modelBoundPayload).containsPattern("CU_[0-9a-f]{6}").containsPattern("EM_[0-9a-f]{6}");
        // Non-sensitive columns must survive, or aggregation questions break.
        assertThat(modelBoundPayload).contains("Austin").contains("\"CustomerId\":1");
    }

    @Test
    void tokensAreDeterministicAcrossCalls() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];

        String first = guarded.call("{\"entity\":\"Customer\"}");
        String second = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(first).isEqualTo(second);
        assertThat(session.tokenCount()).isEqualTo(4);
    }

    @Test
    void finalAnswerRemainsPseudonymizedForTheUser() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];
        String payload = guarded.call("{\"entity\":\"Customer\"}");

        String token = payload.replaceAll("(?s).*?(CU_[0-9a-f]{6}).*", "$1");
        String protectedOutput = session.protectOutput("The top customer is " + token + ".");

        assertThat(protectedOutput).isEqualTo("The top customer is " + token + ".");
    }

    @Test
    void unparseableToolResultIsWithheldRatherThanForwarded() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback("<html>Jane Doe</html>") })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void sensitiveValuesAreTokenizedInsideTheMcpEnvelope() {
        // The real DAB/MCP shape: rows arrive as JSON encoded *inside* content[0].text.
        String envelope = """
                {"content":[{"type":"text","text":"{\\"entity\\":\\"Customer\\",\\"result\\":{\\"value\\":[\
                {\\"CustomerId\\":1,\\"FullName\\":\\"Jane Doe\\",\\"Email\\":\\"jane.doe@example.com\\"}]}}"}],\
                "isError":false}
                """;
        SensitiveDataGuard.Session session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload)
                .as("values nested inside the MCP text envelope must still be tokenized")
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL);
        assertThat(modelBoundPayload).containsPattern("CU_[0-9a-f]{6}");
        assertThat(session.tokenCount()).isEqualTo(2);
    }

    @Test
    void inboundPiiIsTokenizedBeforeItCanReachMemoryOrTheModel() {
        SensitiveDataGuard.Session session = this.guard.newSession();

        String protectedInput = session.protectInput(
                "Find customer named Jane Doe with jane.doe@example.com or phone 415-555-0101 on 2025-01-08.");

        assertThat(protectedInput)
                .doesNotContain("Jane Doe")
                .doesNotContain("jane.doe@example.com")
                .doesNotContain("415-555-0101")
                .contains("2025-01-08")
                .containsPattern("CU_[0-9a-f]{6}")
                .containsPattern("EM_[0-9a-f]{6}")
                .containsPattern("PH_[0-9a-f]{6}");
    }

    @Test
    void protectedToolArgumentsAreRestoredOnlyForDab() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        String protectedInput = session.protectInput("FullName eq 'Jane Doe'");
        String token = protectedInput.replaceAll(".*'(CU_[0-9a-f]{6})'.*", "$1");
        AtomicReference<String> dabInput = new AtomicReference<>();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records").description("read records")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                dabInput.set(toolInput);
                return "{\"value\":[]}";
            }
        };

        session.wrap(new ToolCallback[] { delegate })[0]
                .call("{\"entity\":\"Customer\",\"filter\":\"FullName eq '" + token + "'\"}");

        assertThat(dabInput.get()).contains("Jane Doe").doesNotContain(token);
    }

    @Test
    void toolIntentLogsNeverIncludeFilterValues() {
        String rendered = ToolCallIntent.render(JsonMapper.builder().build(),
                "{\"entity\":\"Customer\",\"filter\":\"FullName eq 'Jane Doe'\"}");

        assertThat(rendered).contains("Customer", "<redacted>").doesNotContain("Jane Doe");
    }

    @Test
    void finalOutputRedactsProviderGeneratedContactDetails() {
        SensitiveDataGuard.Session session = this.guard.newSession();

        String protectedOutput = session.protectOutput("Contact invented@example.com or 415-555-0101.");

        assertThat(protectedOutput).isEqualTo("Contact [REDACTED_EMAIL] or [REDACTED_PHONE].");
    }

    @Test
    void unparseableTextInsideMcpEnvelopeIsWithheld() {
        String envelope = "{\"content\":[{\"type\":\"text\",\"text\":\"{Jane Doe\"}],\"isError\":false}";
        SensitiveDataGuard.Session session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0].call("{}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void unknownTokenInModelOutputIsLeftAlone() {
        SensitiveDataGuard.Session session = this.guard.newSession();
        session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0].call("{}");

        assertThat(session.protectOutput("Unknown CU_ffffff here")).isEqualTo("Unknown CU_ffffff here");
    }
}
