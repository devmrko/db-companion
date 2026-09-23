package com.dbcompanion.model;

import java.util.List;
import com.dbcompanion.common.i18n.UiNotice;
import com.fasterxml.jackson.annotation.JsonIgnore;

public final class MetadataHistory {
    private MetadataHistory() {}
    public record State(boolean installed, boolean enabled, boolean healthy, String message,
                        String beforeTrigger, String afterTrigger, String triggerOwner,
                        boolean canManage, String managementMessage,
                        @JsonIgnore UiNotice messageNotice, @JsonIgnore UiNotice managementNotice) {
        public State(boolean installed,boolean enabled,boolean healthy,String message,String beforeTrigger,
                     String afterTrigger,String triggerOwner,boolean canManage,String managementMessage) {
            this(installed,enabled,healthy,message,beforeTrigger,afterTrigger,triggerOwner,canManage,managementMessage,null,null);
        }
        public State(boolean installed,boolean enabled,boolean healthy,UiNotice message,String beforeTrigger,
                     String afterTrigger,String triggerOwner,boolean canManage,String managementMessage) {
            this(installed,enabled,healthy,"",beforeTrigger,afterTrigger,triggerOwner,canManage,managementMessage,message,null);
        }
        @Override public String message() { return messageNotice==null?message:messageNotice.render(); }
        @Override public String managementMessage() { return managementNotice==null?managementMessage:managementNotice.render(); }
        public State withAccess(boolean allowed, String reason) {
            return new State(installed, enabled, healthy, message, beforeTrigger, afterTrigger, triggerOwner, allowed, reason,messageNotice,null);
        }
        public State withAccess(boolean allowed, UiNotice reason) {
            return new State(installed, enabled, healthy, message, beforeTrigger, afterTrigger, triggerOwner, allowed, "",messageNotice,reason);
        }
    }
    public record Configuration(boolean installed, boolean needsUpgrade, boolean enabled, String triggerOwner) {}
    public record Change(String seq, String eventId, String changedAt, String changedBy, String schema,
                         String table, String column, String kind, String annotationName, String beforeJson, String afterJson) {}
    public record Page(List<Change> entries, int page, boolean hasNext) {}
    public record Toggle(String schema, String table, boolean enabled) {}
}
