package com.dbcompanion.model;

import java.util.List;

public final class ProfileHistory {
    private ProfileHistory() {}
    public record Target(String schema, String profile) {}
    public record Toggle(String schema, boolean enabled) {}
    /** enabled=null means unknown, never OFF; archive reads have no audit privilege requirement. */
    public record State(boolean installed, Boolean enabled, boolean canManage, boolean canCollect, String policy, String message) {}
    public record RequestValue(String profile, String operation, String attribute, String value, String quality) {}
    public record Entry(long seq, String profile, String eventAt, String actor, String kind, String payload, String storage) {
        public Entry(long seq,String profile,String eventAt,String actor,String kind,String payload){this(seq,profile,eventAt,actor,kind,payload,"COMMON");}
    }
    public record Page(List<Entry> entries, int page, boolean hasNext) {}
    public record Collected(int auditRows, int entries, boolean more, boolean snapshotSaved) {}
}
