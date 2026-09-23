package com.dbcompanion.repository;

import com.dbcompanion.model.TableStructure.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Dictionary reads only; every query is scoped to the selected table or its referenced keys. */
@Repository
public class TableStructureRepository {
    private final JdbcTemplate jdbc;
    public TableStructureRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Constraint> constraints(String schema, String table) {
        var definitions = jdbc.query("""
                SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, R_OWNER, R_CONSTRAINT_NAME,
                       DELETE_RULE, STATUS, VALIDATED, DEFERRABLE, DEFERRED, INDEX_OWNER, INDEX_NAME,
                       SEARCH_CONDITION
                FROM SYS.ALL_CONSTRAINTS WHERE OWNER = ? AND TABLE_NAME = ?
                ORDER BY CASE CONSTRAINT_TYPE WHEN 'P' THEN 0 WHEN 'R' THEN 1 WHEN 'U' THEN 2 ELSE 3 END,
                         CONSTRAINT_NAME
                """, (row, n) -> {
                    // Read LONG last and in column order for Oracle's streaming LONG handling.
                    var fields = new String[12];
                    for (int i = 0; i < fields.length; i++) fields[i] = row.getString(i + 1);
                    return new Constraint(fields[0], fields[1], List.of(), fields[2], fields[3], null,
                            List.of(), fields[11], fields[4], fields[5], fields[6], fields[7], fields[8], fields[9], fields[10]);
                }, schema, table);
        if (definitions.isEmpty()) return List.of();
        var columns = new HashMap<String, List<String>>();
        jdbc.query("""
                SELECT CONSTRAINT_NAME, COLUMN_NAME FROM SYS.ALL_CONS_COLUMNS
                WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, POSITION
                """, (RowCallbackHandler) row -> columns.computeIfAbsent(row.getString(1), k -> new ArrayList<>())
                .add(row.getString(2)), schema, table);
        var referenced = definitions.stream().filter(c -> c.referenceName() != null)
                .map(c -> new Key(c.referenceOwner(), c.referenceName())).distinct().toList();
        var targetTables = new HashMap<Key, String>();
        var targetColumns = new HashMap<Key, List<String>>();
        batches(referenced, (placeholders, args) -> {
            jdbc.query("SELECT OWNER, CONSTRAINT_NAME, TABLE_NAME FROM SYS.ALL_CONSTRAINTS WHERE (OWNER, CONSTRAINT_NAME) IN ("
                    + placeholders + ")", (RowCallbackHandler) row -> targetTables.put(
                    new Key(row.getString(1), row.getString(2)), row.getString(3)), args);
            jdbc.query("SELECT OWNER, CONSTRAINT_NAME, COLUMN_NAME FROM SYS.ALL_CONS_COLUMNS WHERE (OWNER, CONSTRAINT_NAME) IN ("
                    + placeholders + ") ORDER BY OWNER, CONSTRAINT_NAME, POSITION", (RowCallbackHandler) row ->
                    targetColumns.computeIfAbsent(new Key(row.getString(1), row.getString(2)), k -> new ArrayList<>())
                            .add(row.getString(3)), args);
        });
        return definitions.stream().map(c -> {
            var target = new Key(c.referenceOwner(), c.referenceName());
            return new Constraint(c.name(), c.type(), List.copyOf(columns.getOrDefault(c.name(), List.of())),
                    c.referenceOwner(), c.referenceName(), targetTables.get(target),
                    List.copyOf(targetColumns.getOrDefault(target, List.of())), c.condition(), c.deleteRule(),
                    c.status(), c.validated(), c.deferrable(), c.deferred(), c.indexOwner(), c.indexName());
        }).toList();
    }

    public List<Index> indexes(String schema, String table) {
        var indexes = jdbc.query("""
                SELECT OWNER, INDEX_NAME, INDEX_TYPE, UNIQUENESS, STATUS, VISIBILITY, PARTITIONED
                FROM SYS.ALL_INDEXES WHERE TABLE_OWNER = ? AND TABLE_NAME = ? ORDER BY INDEX_NAME
                """, (row, n) -> new Index(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6), row.getString(7), List.of()), schema, table);
        var columns = new HashMap<Key, List<IndexColumn>>();
        var expressions = new HashMap<Key, Map<Integer, String>>();
        batches(indexes.stream().filter(i -> i.type().startsWith("FUNCTION-BASED"))
                .map(i -> new Key(i.owner(), i.name())).toList(), (placeholders, args) ->
                jdbc.query("SELECT INDEX_OWNER, INDEX_NAME, COLUMN_POSITION, COLUMN_EXPRESSION FROM SYS.ALL_IND_EXPRESSIONS "
                        + "WHERE (INDEX_OWNER, INDEX_NAME) IN (" + placeholders + ")", (RowCallbackHandler) row ->
                        expressions.computeIfAbsent(new Key(row.getString(1), row.getString(2)), k -> new HashMap<>())
                                .put(row.getInt(3), row.getString(4)), args));
        // Filter by index identity, including bitmap join indexes whose columns belong to another table.
        batches(indexes.stream().map(i -> new Key(i.owner(), i.name())).toList(), (placeholders, args) ->
                jdbc.query("SELECT INDEX_OWNER, INDEX_NAME, COLUMN_POSITION, COLUMN_NAME, DESCEND FROM SYS.ALL_IND_COLUMNS "
                        + "WHERE (INDEX_OWNER, INDEX_NAME) IN (" + placeholders + ") ORDER BY INDEX_OWNER, INDEX_NAME, COLUMN_POSITION",
                        (RowCallbackHandler) row -> {
                            var key = new Key(row.getString(1), row.getString(2));
                            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(new IndexColumn(row.getInt(3),
                                    row.getString(4), row.getString(5), expressions.getOrDefault(key, Map.of()).get(row.getInt(3))));
                        }, args));
        return indexes.stream().map(i -> new Index(i.owner(), i.name(), i.type(), i.uniqueness(), i.status(),
                i.visibility(), i.partitioned(), List.copyOf(columns.getOrDefault(new Key(i.owner(), i.name()), List.of())))).toList();
    }

    private void batches(List<Key> keys, BiConsumer<String, Object[]> query) {
        for (int start = 0; start < keys.size(); start += 500) {
            var batch = keys.subList(start, Math.min(start + 500, keys.size()));
            var args = new ArrayList<Object>();
            batch.forEach(key -> { args.add(key.owner()); args.add(key.name()); });
            query.accept(String.join(",", java.util.Collections.nCopies(batch.size(), "(?, ?)")), args.toArray());
        }
    }
}
