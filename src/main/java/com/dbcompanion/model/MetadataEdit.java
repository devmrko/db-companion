package com.dbcompanion.model;

import java.util.List;

public final class MetadataEdit {
    private MetadataEdit() {}
    public record Target(String schema, String table, String column) {
        public Target { if (column != null && column.isEmpty()) column = null; }
    }
    public record Value(boolean present, String value, boolean inherited) {}
    public record Form(Target target, String kind, String mode, String name, String value,
                       String version, List<String> columns) {}
    public record SaveRequest(String schema, String table, String column, String kind, String mode,
                              String name, String value, String version) {
        public Target target() { return new Target(schema, table, column); }
    }
    public record SaveResult(boolean verified, String message) {}
}
