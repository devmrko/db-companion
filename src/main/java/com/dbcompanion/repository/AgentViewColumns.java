package com.dbcompanion.repository;

import java.util.List;

/** Resolves only columns actually returned by JDBC metadata. */
public record AgentViewColumns(List<String> names) {
    public AgentViewColumns { names = List.copyOf(names); }

    public String optional(String... candidates) {
        for (String candidate : candidates) {
            for (String name : names) {
                if (name.equalsIgnoreCase(candidate)) return "\"" + name.replace("\"", "\"\"") + "\"";
            }
        }
        return null;
    }

    public String required(String... candidates) {
        String column = optional(candidates);
        if (column == null) throw new IllegalStateException("Required catalog column missing: " + String.join("/", candidates));
        return column;
    }

    public String projection(String... candidates) {
        String column = optional(candidates);
        return column == null ? "NULL" : column;
    }
}
