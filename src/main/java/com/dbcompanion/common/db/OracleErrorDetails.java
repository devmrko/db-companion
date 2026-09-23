package com.dbcompanion.common.db;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Oracle error rows for the authenticated caller, never for application logs. */
public final class OracleErrorDetails {
    private static final Pattern ERROR_ROW = Pattern.compile("^(?:ORA|PLS)-[0-9]{5}:.*$");
    private OracleErrorDetails() {}

    public static String forDisplay(Throwable error) {
        if (error == null) return "";
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var rows = new LinkedHashSet<String>();
        pending.add(error);
        while (!pending.isEmpty() && visited.size() < 32 && rows.size() < 32) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                if (sql.getMessage() != null) {
                    sql.getMessage().lines().limit(128).map(String::strip)
                            .filter(row -> ERROR_ROW.matcher(row).matches())
                            .limit(32 - rows.size())
                            .map(row -> row.length() > 1024 ? row.substring(0, 1024) + "…" : row)
                            .forEach(rows::add);
                }
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return String.join("\n", rows);
    }
}
