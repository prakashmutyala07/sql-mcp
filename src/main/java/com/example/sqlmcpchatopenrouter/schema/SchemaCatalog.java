package com.example.sqlmcpchatopenrouter.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.sqlmcpchatopenrouter.config.AppProperties;

/**
 * Reads table, column and foreign-key metadata straight from SQL Server and renders it
 * as the compact block injected into the system prompt.
 *
 * <p>Foreign keys are the reason this exists: the DAB config declares no {@code relationships},
 * so {@code describe_entities} cannot tell the model how entities join. {@code sys.foreign_keys}
 * can.
 *
 * <p>Extraction is fail-soft. If the database is unreachable the app still starts and still
 * chats; the model simply loses the relationship hints and must lean on {@code describe_entities}.
 *
 * <p>{@link #entities()} exposes the same metadata in structured form. Embedding those records
 * and swapping in a pgvector store later is a config change, not a rewrite of this class.
 */
@Component
public class SchemaCatalog {

    private static final Logger logger = LoggerFactory.getLogger(SchemaCatalog.class);

    private static final String COLUMN_SQL = """
            SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE,
                   CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS IS_PK
            FROM INFORMATION_SCHEMA.COLUMNS c
            LEFT JOIN (
                SELECT k.TABLE_SCHEMA, k.TABLE_NAME, k.COLUMN_NAME
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
                  ON t.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                 AND t.TABLE_SCHEMA = k.TABLE_SCHEMA
                WHERE t.CONSTRAINT_TYPE = 'PRIMARY KEY'
            ) pk ON pk.TABLE_SCHEMA = c.TABLE_SCHEMA
                AND pk.TABLE_NAME = c.TABLE_NAME
                AND pk.COLUMN_NAME = c.COLUMN_NAME
            WHERE c.TABLE_SCHEMA = ?
            ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String FOREIGN_KEY_SQL = """
            SELECT tp.name AS PARENT_TABLE, cp.name AS PARENT_COLUMN,
                   tr.name AS REF_TABLE,    cr.name AS REF_COLUMN
            FROM sys.foreign_keys fk
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.tables  tp ON tp.object_id = fkc.parent_object_id
            JOIN sys.columns cp ON cp.object_id = fkc.parent_object_id
                               AND cp.column_id = fkc.parent_column_id
            JOIN sys.tables  tr ON tr.object_id = fkc.referenced_object_id
            JOIN sys.columns cr ON cr.object_id = fkc.referenced_object_id
                               AND cr.column_id = fkc.referenced_column_id
            JOIN sys.schemas s  ON s.schema_id = tp.schema_id
            WHERE s.name = ?
            ORDER BY tp.name, cp.name
            """;

    private final AppProperties properties;

    /** Rendered once at first use; the schema does not change while the app runs. */
    private volatile String renderedSchema;

    private volatile List<Entity> entities = List.of();

    public SchemaCatalog(AppProperties properties) {
        this.properties = properties;
    }

    /** Compact schema block for the system prompt; empty string when unavailable. */
    public String render() {
        String cached = this.renderedSchema;
        if (cached == null) {
            synchronized (this) {
                if (this.renderedSchema == null) {
                    this.renderedSchema = load();
                }
                cached = this.renderedSchema;
            }
        }
        return cached;
    }

    /** Structured view of the same metadata, for a future vector-store backed retriever. */
    public List<Entity> entities() {
        render();
        return this.entities;
    }

    private String load() {
        long startedAt = System.nanoTime();
        if (!this.properties.schema().enabled()) {
            logger.info("Schema introspection disabled (app.schema.enabled=false).");
            return "";
        }
        if (!StringUtils.hasText(this.properties.schema().jdbcUrl())) {
            logger.warn("No schema JDBC URL configured; skipping schema introspection.");
            return "";
        }
        String catalog = this.properties.schema().catalog();
        try {
            JdbcClient client = JdbcClient.create(schemaDataSource(this.properties.schema()));
            Map<String, List<Column>> columnsByTable = new LinkedHashMap<>();
            client.sql(COLUMN_SQL).param(catalog).query((rs, rowNum) -> {
                columnsByTable.computeIfAbsent(rs.getString("TABLE_NAME"), key -> new ArrayList<>())
                        .add(new Column(rs.getString("COLUMN_NAME"), rs.getString("DATA_TYPE"),
                                rs.getInt("IS_PK") == 1));
                return null;
            }).list();

            List<ForeignKey> foreignKeys = client.sql(FOREIGN_KEY_SQL).param(catalog)
                    .query((rs, rowNum) -> new ForeignKey(rs.getString("PARENT_TABLE"), rs.getString("PARENT_COLUMN"),
                            rs.getString("REF_TABLE"), rs.getString("REF_COLUMN")))
                    .list();

            List<Entity> loaded = columnsByTable.entrySet().stream()
                    .map(entry -> new Entity(entry.getKey(), entry.getValue(),
                            foreignKeys.stream().filter(fk -> fk.parentTable().equals(entry.getKey())).toList()))
                    .toList();
            this.entities = loaded;

            logger.info("Schema introspection complete: {} table(s), {} foreign key(s) in [{}] in {} ms.",
                    loaded.size(), foreignKeys.size(), catalog, elapsedMillis(startedAt));
            return format(loaded, foreignKeys);
        }
        catch (RuntimeException ex) {
            // Fail soft: a chat without relationship hints beats an app that will not start.
            logger.warn("Schema introspection failed after {} ms; continuing without schema context. cause={}: {}",
                    elapsedMillis(startedAt), ex.getClass().getSimpleName(), ex.getMessage());
            return "";
        }
    }

    private static DriverManagerDataSource schemaDataSource(AppProperties.Schema schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(schema.jdbcUrl());
        if (StringUtils.hasText(schema.jdbcUsername())) {
            dataSource.setUsername(schema.jdbcUsername());
        }
        if (StringUtils.hasText(schema.jdbcPassword())) {
            dataSource.setPassword(schema.jdbcPassword());
        }
        return dataSource;
    }

    private static String format(List<Entity> entities, List<ForeignKey> foreignKeys) {
        StringBuilder text = new StringBuilder();
        for (Entity entity : entities) {
            StringJoiner columns = new StringJoiner(", ");
            entity.columns().forEach(column -> columns.add(column.pk()
                    ? column.name() + " " + column.type() + " PK"
                    : column.name() + " " + column.type()));
            text.append(entity.name()).append('(').append(columns).append(')').append('\n');
        }
        if (!foreignKeys.isEmpty()) {
            text.append("\nRelationships:\n");
            foreignKeys.forEach(fk -> text.append("  ").append(fk.parentTable()).append('.').append(fk.parentColumn())
                    .append(" -> ").append(fk.refTable()).append('.').append(fk.refColumn()).append('\n'));
        }
        return text.toString().strip();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record Column(String name, String type, boolean pk) {
    }

    public record ForeignKey(String parentTable, String parentColumn, String refTable, String refColumn) {
    }

    public record Entity(String name, List<Column> columns, List<ForeignKey> foreignKeys) {
    }
}
