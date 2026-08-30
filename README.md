# SQL MCP Chat OpenRouter

Standalone Spring AI proof of concept for OpenRouter chat models and a local Microsoft Data API Builder MCP server.

## Verified Setup Choices

- Java: 21
- Spring Boot: 4.1.1
- Spring AI BOM: 2.0.1
- Base package: `com.example.sqlmcpchatopenrouter`
- MCP transport: Streamable HTTP, configured through `.env`
- Chat provider: Spring AI OpenAI-compatible starter, pointed at OpenRouter

## Local Configuration

Runtime values are loaded from the local `.env` file via Spring Boot config import.

Required values:

```properties
OPENROUTER_API_KEY=...
TOKEN_SECRET_KEY=...
DAB_MCP_BASE_URL=http://localhost:5001
OPENROUTER_HTTP_REFERER=http://localhost:8080
ECOM_MSSQL_CONNECTION_STRING=...
ECOM_JDBC_URL=...
ECOM_JDBC_USERNAME=...
ECOM_JDBC_PASSWORD=...
```

Other settings such as the model names, MCP endpoint path, completion limit, temperature, and log level have defaults in `application.yml`; add them to `.env` only when overriding locally.

## Current API

Open the chat UI:

```bash
open http://localhost:8080/
```

List DAB MCP tools discovered through Spring AI's Streamable HTTP MCP client:

```bash
curl http://localhost:8080/api/mcp/tools
```

Send a chat request with DAB MCP tools available to the model:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the available database entities.","conversationId":"demo"}'
```

Stream chat progress as browser SSE:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the available database entities.","conversationId":"demo"}'
```

Clear in-memory chat history for a conversation:

```bash
curl -X DELETE http://localhost:8080/api/conversations/demo/memory
```

## Isolation

This project is intentionally independent and does not reference the existing `sql-mcp-chat` project.
