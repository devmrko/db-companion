package com.dbcompanion.model;

import java.util.List;

public final class TeamEdit {
    private TeamEdit() {}
    public record Target(String schema, String team, String attribute) {}
    public record Form(Target target, String value, String version, int maxBytes,
                       List<String> agents, List<String> tasks) {}
    public record SaveRequest(String schema, String team, String attribute, String value, String version) {
        public Target target() { return new Target(schema, team, attribute); }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("Unexpected team edit field: " + name);
        }
    }
    public record SaveResult(boolean verified, String message) {}
    public record HistoryTarget(String schema, String team) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object ignored) { throw new IllegalArgumentException("Unexpected team history field: " + name); }
    }
    public record HistoryRow(String seq, String eventAt, String actor, String attribute, String outcome) {}
    public record HistoryPage(boolean installed, List<HistoryRow> entries, boolean more) {}
    public record HistoryEntry(String seq, String eventAt, String actor, String attribute, String outcome,
                               String beforeJson, String afterJson) {}
}
