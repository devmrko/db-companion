package com.dbcompanion.model;

import java.time.Instant;
import java.util.List;

public final class HistoryAccess {
    private HistoryAccess() {}
    public enum Operation { ENABLE, DISABLE, READ, MANAGE, DENY, INHERIT }
    public record Grant(String user,boolean read,boolean manage,String changedAt,String changedBy) {}
    public record Item(String name,MetadataHistory.State state,List<Grant> grants) {}
    public record Preview(String token,String schema,String profile,String objectList,Instant createdAt,List<Item> items,List<String> users) {}
    public record Apply(String token,List<String> tables,Operation operation,List<String> users,boolean allUsers) {}
    public record Result(String table,boolean success,String message) {}
    public record Inspection(String owner,String schema,String table,String enabled,int healthy,int canRead,int canManage,String beforeTrigger,String afterTrigger) {}
}
