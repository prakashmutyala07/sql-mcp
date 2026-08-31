# Read-only SQL MCP AI Assistant — POC Architecture

## Executive summary

This proof of concept (POC) lets business users ask natural-language questions about SQL Server data through an existing chat interface. A single Spring Boot coordinator applies system instructions, sanitized conversation context, PII safeguards, and structured-response handling. The LLM interprets the request but never connects directly to the database: approved Microsoft Data API Builder (DAB) MCP tools mediate access, mutation tools are disabled, and SQL Server access is read-only. Authentication and enterprise authorization are future production capabilities, not features of the current POC.

## Architecture at a glance

```mermaid
flowchart TB
    subgraph ACCESS["User Access"]
        direction LR
        USER([Business User]) --> UI["User Interface<br/>(Chat)"]
        UI -. "Future production" .-> AUTH["Authentication / OIDC<br/>(Not in current POC)"]
    end

    UI --> API["API / ChatController"]

    subgraph INTELLIGENCE[" "]
        direction LR

        subgraph RUNTIME["Spring Boot AI Application / Agent Runtime"]
            direction LR

            subgraph CORE["Single Lightweight Coordinator"]
                direction TB
                COORD[ChatCoordinator]
                PROMPT["PromptProvider<br/>Strong System Instructions"]
                RUNNER[ChatModelRunner]
                CLIENT[Spring AI ChatClient]

                COORD --> PROMPT --> RUNNER --> CLIENT
            end

            subgraph CONTROLS["Guardrails, Context & Output"]
                direction TB
                PII["SensitiveDataGuard<br/>Input / Output / Tool Results"]
                MEMORY["Sanitized<br/>Conversation Memory"]
                RESPONSE["Structured Response<br/>Summary / Table / Status"]
            end

            COORD --> PII --> RUNNER
            COORD <--> MEMORY
            RUNNER --> RESPONSE
        end

        LLM["OpenRouter LLM<br/>Primary / Fallback"]
    end

    API --> COORD
    CLIENT <--> LLM
    RESPONSE --> API

    subgraph DATA["Controlled Data Access"]
        direction TB

        subgraph TOOLS["DAB MCP Tools — Approved Read Operations"]
            direction LR
            DESCRIBE[describe_entities]
            READ[read_records]
            AGGREGATE[aggregate_records]
        end

        DAB[Microsoft Data API Builder]
        SQL[("SQL Server<br/>Read-only Access")]

        DESCRIBE --> DAB
        READ --> DAB
        AGGREGATE --> DAB
        DAB --> SQL
    end

    CLIENT -->|Guarded tool callbacks| DESCRIBE
    CLIENT -->|Guarded tool callbacks| READ
    CLIENT -->|Guarded tool callbacks| AGGREGATE
    DAB -. "Tool results protected before model use" .-> PII

    classDef ui fill:#dbeafe,stroke:#60a5fa,color:#0f172a,stroke-width:2px;
    classDef future fill:#eff6ff,stroke:#60a5fa,color:#475569,stroke-width:2px,stroke-dasharray:6 4;
    classDef runtime fill:#dcfce7,stroke:#16a34a,color:#052e16,stroke-width:2px;
    classDef llm fill:#fef3c7,stroke:#eab308,color:#422006,stroke-width:2px;
    classDef security fill:#f3e8ff,stroke:#a855f7,color:#3b0764,stroke-width:2px;
    classDef support fill:#fff7ed,stroke:#f97316,color:#431407,stroke-width:2px;
    classDef data fill:#ecfdf5,stroke:#10b981,color:#022c22,stroke-width:2px;

    class USER,UI,API ui;
    class AUTH future;
    class COORD,PROMPT,RUNNER,CLIENT runtime;
    class LLM llm;
    class PII security;
    class MEMORY,RESPONSE support;
    class DESCRIBE,READ,AGGREGATE,DAB,SQL data;

    style ACCESS fill:#f8fbff,stroke:#bfdbfe,stroke-width:1px
    style INTELLIGENCE fill:none,stroke:none
    style RUNTIME fill:#f7fff9,stroke:#10b981,stroke-width:2px,stroke-dasharray:8 5
    style CORE fill:#f0fdf4,stroke:#86efac,stroke-width:1px
    style CONTROLS fill:#fffaf5,stroke:#fdba74,stroke-width:1px
    style DATA fill:#f8fafc,stroke:#94a3b8,stroke-width:2px
    style TOOLS fill:#f0fdf4,stroke:#6ee7b7,stroke-width:1px
```

The dotted boundary is the current **single-coordinator Spring Boot runtime**. It is not a multi-agent design. The yellow LLM is external to that runtime, and the lower layer is the only route to SQL Server data.

## Runtime flow

```mermaid
sequenceDiagram
    actor User
    participant UI as Chat UI
    participant API as ChatController
    participant Coordinator as ChatCoordinator
    participant Guard as SensitiveDataGuard
    participant Prompt as PromptProvider
    participant Runner as ChatModelRunner
    participant Client as Spring AI ChatClient
    participant LLM as OpenRouter LLM
    participant MCP as DAB MCP Tools
    participant DAB as Microsoft DAB
    participant SQL as SQL Server

    User->>UI: Ask a natural-language question
    UI->>API: Send message and optional conversation ID
    API->>API: Validate request
    API->>Coordinator: Start chat turn
    Coordinator->>Coordinator: Resolve or create conversation ID
    Coordinator->>Guard: Protect sensitive input
    Guard-->>Coordinator: Protected message
    Coordinator->>Prompt: Get system instructions
    Coordinator->>Coordinator: Apply sanitized conversation context
    Coordinator->>Runner: Run protected request
    Runner->>Client: Supply prompt, memory, and guarded tools
    Client->>LLM: Request interpretation and answer
    LLM-->>Client: Request an approved MCP tool
    Client->>MCP: Invoke describe, read, or aggregate
    MCP->>DAB: Execute approved operation
    DAB->>SQL: Read data
    SQL-->>DAB: Query result
    DAB-->>MCP: Raw tool result
    MCP->>Guard: Protect sensitive result fields
    Guard-->>Client: Protected tool result
    Client->>LLM: Continue with protected data
    LLM-->>Client: Structured model content
    Client-->>Runner: Model response
    Runner->>Guard: Apply final output protection
    Guard-->>Coordinator: Protected structured content
    Coordinator->>Coordinator: Store sanitized turn in memory
    Coordinator-->>API: Structured response
    API-->>UI: Stream UI-ready result
    UI-->>User: Show summary, table, or clarification
```

## Safety controls

- The LLM does not directly access SQL Server.
- DAB MCP exposes only approved describe, read, and aggregate tools; mutation tools are disabled.
- SQL Server access uses a dedicated read-only database role.
- PII is protected in user input, model output, and database tool results.
- Conversation memory stores only sanitized context and remains in memory for the POC.
- A strong system prompt reinforces read-only behavior, schema grounding, prompt-injection resistance, and hallucination prevention.
- A typed structured response makes UI rendering predictable and turns parsing failures into controlled errors.
- Prompting is not the sole security control: application guardrails, DAB configuration, and SQL Server permissions enforce the key boundaries.

## Component responsibilities

| Component | Responsibility |
|---|---|
| Chat UI | Collects questions and renders streamed, structured results. |
| ChatController | Validates API requests and delegates chat turns. |
| ChatCoordinator | Coordinates one request across privacy, prompt, memory, tools, model, and response handling. |
| PromptProvider | Supplies strong system instructions and request-time date context. |
| SensitiveDataGuard | Protects PII in input, tool results, logs, and final output. |
| ChatModelRunner | Applies primary/fallback model policy and converts model output to the response contract. |
| Spring AI ChatClient | Connects the application to OpenRouter and guarded MCP tool callbacks. |
| OpenRouter LLM | Interprets questions and requests tools; it has no direct database connection. |
| Sanitized Conversation Memory | Retains bounded, protected context for simple follow-up questions. |
| Structured Response | Carries status, summary, tabular data, notes, and follow-up information to the UI. |
| DAB MCP Tools | Provide approved schema discovery, record reading, and aggregation operations. |
| Microsoft DAB | Mediates the configured entities and read-only operations. |
| SQL Server | Stores source data and enforces read-only access for the DAB identity. |

## Current scope and future scope

| Current POC | Future / Production |
|---|---|
| Natural-language database questions | Production authentication with OIDC |
| Read-only data access | Role-based authorization (RBAC) |
| Structured UI response | AI evaluation and regression testing |
| PII-safe output and protected tool results | Schema RAG if schema size or complexity grows |
| Simple follow-up context using sanitized memory | Multi-agent orchestration only if distinct business domains require it |

## How to explain this POC in one minute

> This POC demonstrates a controlled way to connect AI with enterprise SQL data. The user asks a natural-language question. The Spring Boot application applies PII protection, strong instructions, conversation context, and structured response handling. The LLM helps interpret the request, but it does not directly access the database. Data access happens only through Microsoft DAB MCP tools using read-only SQL Server access.
