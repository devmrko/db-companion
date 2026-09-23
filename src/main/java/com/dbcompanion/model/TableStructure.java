package com.dbcompanion.model;

import java.util.List;

public final class TableStructure {
    private TableStructure() {}

    public record Key(String owner, String name) {}
    public record Constraint(String name, String type, List<String> columns,
                             String referenceOwner, String referenceName, String referenceTable,
                             List<String> referenceColumns, String condition, String deleteRule,
                             String status, String validated, String deferrable, String deferred,
                             String indexOwner, String indexName) {
        public String typeLabel() {
            return switch (type) {
                case "P" -> "PK";
                case "R" -> "FK";
                case "U" -> "UNIQUE";
                case "C" -> "CHECK / NOT NULL";
                default -> type;
            };
        }
    }
    public record IndexColumn(int position, String name, String direction, String expression) {
        public String label() { return (expression == null ? name : expression) + " " + direction; }
    }
    public record Index(String owner, String name, String type, String uniqueness, String status,
                        String visibility, String partitioned, List<IndexColumn> columns) {}
}
