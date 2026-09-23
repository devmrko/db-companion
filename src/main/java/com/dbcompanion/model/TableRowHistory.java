package com.dbcompanion.model;

import java.util.List;

public final class TableRowHistory {
    private TableRowHistory() {}
    public record State(String code,String reason,String detail,String archive,Long tableId,String signature,String triggerName,
                        boolean canPrepare,boolean canInstall,boolean canEnable,boolean canDisable,List<String> columns,List<String> excluded) {
        public State {columns=List.copyOf(columns);excluded=List.copyOf(excluded);}
    }
    public record Request(String schema,String table,Long tableId,String signature,String action) {}
    public record Entry(String seq,String tableId,String eventAt,String actor,String operation,String beforeJson,String afterJson) {}
    public record Page(List<Entry> entries,int page,boolean more) {public Page{entries=List.copyOf(entries);}}
}
