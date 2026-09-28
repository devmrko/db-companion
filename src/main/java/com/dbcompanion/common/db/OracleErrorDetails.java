package com.dbcompanion.common.db;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.transaction.TransactionSystemException;

/** Oracle error rows for the authenticated caller, never for application logs. */
public final class OracleErrorDetails {
    private static final Pattern ERROR_ROW = Pattern.compile("^(?:ORA|PLS)-[0-9]{5}:.*$");
    private OracleErrorDetails() {}

    public static String forDisplay(Throwable error) {
        var rows = new LinkedHashSet<String>();
        for (var sql : sqlExceptions(error)) {
            if (rows.size() >= 32) break;
            if (sql.getMessage() != null) {
                sql.getMessage().lines().limit(128).map(String::strip)
                        .filter(row -> ERROR_ROW.matcher(row).matches())
                        .limit(32 - rows.size())
                        .map(row -> row.length() > 1024 ? row.substring(0, 1024) + "…" : row)
                        .forEach(rows::add);
            }
        }
        return String.join("\n", rows);
    }

    public static int firstCode(Throwable error) {
        return sqlExceptions(error).stream().mapToInt(SQLException::getErrorCode).filter(code -> code != 0).findFirst().orElse(0);
    }

    /** Preserve the application failure before rollback/close failures, without wrapper SQL text. */
    private static List<SQLException> sqlExceptions(Throwable error) {
        if (error == null) return List.of();
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var errors = new ArrayList<SQLException>();
        pending.add(error);
        while (!pending.isEmpty() && visited.size() < 32) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            var suppressed = current.getSuppressed();
            for (int i = Math.min(suppressed.length, 32) - 1; i >= 0; i--) pending.addFirst(suppressed[i]);
            if (current instanceof SQLException sql) {
                errors.add(sql);
                if (sql.getNextException() != null) pending.addFirst(sql.getNextException());
            }
            if (current.getCause() != null) pending.addFirst(current.getCause());
            if (current instanceof TransactionSystemException transaction && transaction.getApplicationException() != null)
                pending.addFirst(transaction.getApplicationException());
        }
        return errors;
    }
}
