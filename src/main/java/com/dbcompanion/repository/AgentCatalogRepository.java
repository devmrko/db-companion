package com.dbcompanion.repository;

import com.dbcompanion.model.AgentCatalog.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/** Fixed dictionary views only; all schema, object name and ID values are bound. */
@Repository
public class AgentCatalogRepository {
    private final JdbcTemplate jdbc;

    public AgentCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Item> items(Scope scope, Kind kind, List<String> names) {
        if (names != null && names.isEmpty()) return List.of();
        String view = (scope.own() ? "USER_" : "DBA_") + kind.view;
        var columns = describe(view);
        var result = new ArrayList<Item>();
        for (var query : itemQueries(scope, kind, names, columns)) {
            result.addAll(jdbc.query(query.sql(), (row, index) -> new Item(row.getString(1), row.getString(2),
                    row.getString(3), row.getString(4), row.getString(5), row.getString(6)), query.args().toArray()));
        }
        return List.copyOf(result);
    }

    /** Null names means the schema's whole list; an empty selection means no query. */
    static List<ItemQuery> itemQueries(Scope scope, Kind kind, List<String> names, AgentViewColumns columns) {
        if (names != null && names.isEmpty()) return List.of();
        String view = (scope.own() ? "USER_" : "DBA_") + kind.view;
        String nameColumn = columns.required(kind.nameColumns());
        var result = new ArrayList<ItemQuery>();
        int size = names == null ? 1 : names.size();
        for (int start = 0; start < size; start += 500) {
            var args = new ArrayList<Object>();
            String sql = "SELECT " + columns.projection(kind.idColumns()) + ", " + nameColumn
                    + ", " + columns.projection("DESCRIPTION") + ", " + columns.projection("STATUS")
                    + ", " + columns.projection("CREATED") + ", " + columns.projection("LAST_MODIFIED")
                    + " FROM " + view + " WHERE 1=1";
            if (!scope.own()) { sql += " AND " + columns.required("OWNER") + " = ?"; args.add(scope.schema()); }
            if (names != null) {
                var batch = names.subList(start, Math.min(start + 500, size));
                sql += " AND " + nameColumn + " IN (" + placeholders(batch.size()) + ")";
                args.addAll(batch);
            }
            sql += " ORDER BY " + nameColumn;
            result.add(new ItemQuery(sql, args));
        }
        return List.copyOf(result);
    }

    record ItemQuery(String sql, List<Object> args) {
        ItemQuery { args = List.copyOf(args); }
    }

    public List<Attribute> attributes(Scope scope, Kind kind, List<Item> items) {
        if (items.isEmpty()) return List.of();
        String view = (scope.own() ? "USER_" : "DBA_") + kind.attributesView;
        var columns = describe(view);
        String nameColumn = columns.optional(kind.nameColumns());
        if (nameColumn == null && kind == Kind.TOOL) nameColumn = columns.optional("AGENT_NAME");
        String keyColumn = nameColumn != null ? nameColumn : columns.required(kind.idColumns());
        Map<String, String> objectNames = new HashMap<>();
        for (var item : items) {
            String key = nameColumn != null ? item.name() : item.id();
            if (key == null) throw new IllegalStateException("Catalog has neither a shared name nor ID column");
            objectNames.put(key, item.name());
        }
        var keys = List.copyOf(objectNames.keySet());
        var result = new ArrayList<Attribute>();
        for (int start = 0; start < keys.size(); start += 500) {
            var batch = keys.subList(start, Math.min(start + 500, keys.size()));
            var args = new ArrayList<Object>(batch);
            String sql = "SELECT " + keyColumn + ", " + columns.required("ATTRIBUTE_NAME")
                    + ", " + columns.required("ATTRIBUTE_VALUE") + ", " + columns.projection("LAST_MODIFIED")
                    + " FROM " + view + " WHERE " + keyColumn + " IN (" + placeholders(batch.size()) + ")";
            if (!scope.own()) { sql += " AND " + columns.required("OWNER") + " = ?"; args.add(scope.schema()); }
            sql += " ORDER BY " + keyColumn + ", " + columns.required("ATTRIBUTE_NAME");
            result.addAll(jdbc.query(sql, (row, index) -> new Attribute(objectNames.get(row.getString(1)), row.getString(2),
                    row.getString(3), row.getString(4)), args.toArray()));
        }
        return List.copyOf(result);
    }

    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }

    private AgentViewColumns describe(String view) {
        AgentViewColumns columns;
        try {
            columns = jdbc.query("SELECT * FROM " + view + " WHERE 1=0", (ResultSetExtractor<AgentViewColumns>) rows -> {
            var metadata = rows.getMetaData();
            var names = new ArrayList<String>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) names.add(metadata.getColumnLabel(i));
            return new AgentViewColumns(names);
            });
        } catch (org.springframework.dao.DataAccessException ex) {
            var cause = ex.getMostSpecificCause();
            int code = cause instanceof java.sql.SQLException sql ? sql.getErrorCode() : 0;
            org.slf4j.LoggerFactory.getLogger(AgentCatalogRepository.class).warn("Agent catalog describe error: view={} code={}", view, code);
            throw ex;
        }
        org.slf4j.LoggerFactory.getLogger(AgentCatalogRepository.class).info("Agent catalog view={} columns={}", view, columns.names());
        return columns;
    }
}
