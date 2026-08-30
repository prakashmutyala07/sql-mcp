package com.example.sqlmcpchatopenrouter.mcp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class McpStartupReporter implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(McpStartupReporter.class);

    private final McpToolCatalog mcpToolCatalog;

    public McpStartupReporter(McpToolCatalog mcpToolCatalog) {
        this.mcpToolCatalog = mcpToolCatalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!this.mcpToolCatalog.isConfigured()) {
            logger.info("DAB MCP client is not configured; skipping startup tool discovery.");
            return;
        }
        List<McpToolCatalog.ToolSummary> tools = this.mcpToolCatalog.tools();
        logger.info("DAB MCP Streamable HTTP connection initialized. {} tool(s) available.", tools.size());
        tools.forEach(tool -> logger.info("DAB MCP tool: {} - {}", tool.name(), tool.description()));
    }
}
