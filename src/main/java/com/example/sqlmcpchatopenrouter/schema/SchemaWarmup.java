package com.example.sqlmcpchatopenrouter.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Primes schema metadata after startup so the first chat request does not pay the
 * SQL Server login/TLS and metadata-query cost.
 */
@Component
public class SchemaWarmup {

    private static final Logger logger = LoggerFactory.getLogger(SchemaWarmup.class);

    private final SchemaCatalog schemaCatalog;

    private final AsyncTaskExecutor chatTaskExecutor;

    public SchemaWarmup(SchemaCatalog schemaCatalog, AsyncTaskExecutor chatTaskExecutor) {
        this.schemaCatalog = schemaCatalog;
        this.chatTaskExecutor = chatTaskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        this.chatTaskExecutor.execute(() -> {
            logger.info("Schema warm-up started.");
            this.schemaCatalog.render();
        });
    }
}
