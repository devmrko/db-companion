package com.dbcompanion.model;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import java.util.Set;

/** Configuration events, not model execution or a full function-source archive. */
public final class SemanticChange {
    public static final String TYPE="SEMANTIC_CHANGE";
    private SemanticChange() {}
    public static MetadataEditException error(int status,String key){return new MetadataEditException(status,key,UiMessages.text("semanticHistory."+key,key));}
    public static void target(String kind,String name){
        if(kind==null||!Set.of("SOURCE","CALLBACK").contains(kind)||name==null||!name.matches("[A-Z][A-Z0-9_$#]{0,127}"))throw error(400,"invalid");
    }
    public record Result(String historyId,boolean verified,String message) {}
    public record Item(String id,String actor,String recordedAt,String state,String kind,String name,String operation) {}
}
