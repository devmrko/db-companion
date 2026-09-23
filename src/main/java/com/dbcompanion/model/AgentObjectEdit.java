package com.dbcompanion.model;

import com.dbcompanion.model.AgentCatalog.Kind;
import java.util.List;

public final class AgentObjectEdit {
    private AgentObjectEdit() {}
    public record Target(String schema, Kind kind, String name, String attribute) {
        public HistoryTarget history() { return new HistoryTarget(schema,kind,name); }
    }
    public record Form(Target target, String value, String version, int maxBytes, boolean historyInstalled,
                       String toolType, List<String> profiles, List<String> credentials,
                       List<String> tools, List<String> tasks) {}
    public record SaveRequest(String schema, Kind kind, String name, String attribute, String value, String version) {
        public Target target() { return new Target(schema,kind,name,attribute); }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key,Object ignored) { throw new IllegalArgumentException("Unexpected object edit field: "+key); }
    }
    public record HistoryTarget(String schema, Kind kind, String name) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key,Object ignored) { throw new IllegalArgumentException("Unexpected object history field: "+key); }
    }
}
