package com.dbcompanion.common.db;

import java.util.*;

public final class AiHistorySql {
    public static final String TABLE="DBC_AI_HISTORY";
    public static final String MARKER="DB Manage Companion AI history v1";
    public static final String PENDING=MARKER+" pending";
    public static final List<String> LEGACY=List.of("DBC_PROFILE_HISTORY","DBC_TEAM_EDIT_HISTORY","DBC_AI_OBJECT_EDIT_HISTORY");
    public static final String NOW="TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP),'YYYY-MM-DD\"T\"HH24:MI:SS.FF6\"Z\"')";
    private AiHistorySql() {}
    public static String table(String schema){return ProfileHistorySql.object(schema,TABLE);}
    public static String create(String schema) {
        return "CREATE TABLE "+table(schema)+"""
                (SEQ NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                 OBJECT_TYPE VARCHAR2(8 CHAR) NOT NULL CHECK (OBJECT_TYPE IN ('PROFILE','TEAM','AGENT','TASK','TOOL')),
                 OBJECT_NAME VARCHAR2(128 CHAR), OBJECT_ID VARCHAR2(128 CHAR), ATTRIBUTE_NAME VARCHAR2(128 CHAR),
                 ACTOR VARCHAR2(128 CHAR) NOT NULL, EVENT_AT VARCHAR2(64 CHAR) NOT NULL,
                 ENTRY_KIND VARCHAR2(16 CHAR) NOT NULL CHECK (ENTRY_KIND IN ('EDIT','SNAPSHOT','REQUEST','UNCLASSIFIED')),
                 OUTCOME VARCHAR2(24 CHAR), SOURCE_KEY VARCHAR2(64 CHAR) NOT NULL, ITEM_NO NUMBER NOT NULL,
                 BEFORE_JSON CLOB CHECK (BEFORE_JSON IS JSON), AFTER_JSON CLOB CHECK (AFTER_JSON IS JSON),
                 PAYLOAD_JSON CLOB CHECK (PAYLOAD_JSON IS JSON),
                 LEGACY_TABLE VARCHAR2(128 CHAR), LEGACY_SEQ NUMBER,
                 RECORDED_AT TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
                 UNIQUE (OBJECT_TYPE,SOURCE_KEY,ITEM_NO), UNIQUE (LEGACY_TABLE,LEGACY_SEQ),
                 CHECK ((ENTRY_KIND='EDIT' AND OBJECT_NAME IS NOT NULL AND BEFORE_JSON IS NOT NULL)
                     OR (ENTRY_KIND='SNAPSHOT' AND OBJECT_TYPE='PROFILE' AND AFTER_JSON IS NOT NULL)
                     OR (ENTRY_KIND IN ('REQUEST','UNCLASSIFIED') AND OBJECT_TYPE='PROFILE' AND PAYLOAD_JSON IS NOT NULL)))
                """;
    }
    public static LinkedHashMap<String,String> mapping(String legacy) {
        if(!LEGACY.contains(legacy))throw new IllegalArgumentException("Unknown history source");
        boolean profile=legacy.equals(LEGACY.getFirst()),team=legacy.equals(LEGACY.get(1));
        var m=new LinkedHashMap<String,String>();
        m.put("OBJECT_TYPE",profile?"'PROFILE'":team?"'TEAM'":"s.OBJECT_TYPE");
        m.put("OBJECT_NAME",profile?"s.PROFILE_NAME":team?"s.TEAM_NAME":"s.OBJECT_NAME");
        m.put("OBJECT_ID",profile?"NULL":team?"s.TEAM_ID":"s.OBJECT_ID");
        m.put("ATTRIBUTE_NAME",profile?"NULL":"s.ATTRIBUTE_NAME");
        m.put("ACTOR","s.ACTOR");
        m.put("EVENT_AT",profile?"s.EVENT_AT":"TO_CHAR(s.EVENT_AT,'YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM')");
        m.put("ENTRY_KIND",profile?"s.KIND":"'EDIT'");
        m.put("OUTCOME",profile?"NULL":"s.OUTCOME");
        m.put("SOURCE_KEY",profile?"s.SOURCE_KEY":"s.REQUEST_ID");
        m.put("ITEM_NO",profile?"s.ITEM_NO":"0");
        m.put("BEFORE_JSON",profile?"TO_CLOB(NULL)":"s.BEFORE_JSON");
        m.put("AFTER_JSON",profile?"CASE WHEN s.KIND='SNAPSHOT' THEN s.PAYLOAD END":"s.AFTER_JSON");
        m.put("PAYLOAD_JSON",profile?"CASE WHEN s.KIND<>'SNAPSHOT' THEN s.PAYLOAD END":"TO_CLOB(NULL)");
        m.put("LEGACY_TABLE","'"+legacy+"'");m.put("LEGACY_SEQ","s.SEQ");
        m.put("RECORDED_AT",profile?"s.RECORDED_AT":"s.EVENT_AT");return m;
    }
    public static String copy(String schema,String legacy) {
        var m=mapping(legacy);
        return "INSERT INTO "+table(schema)+" ("+String.join(",",m.keySet())+") SELECT "+String.join(",",m.values())
                +" FROM "+ProfileHistorySql.object(schema,legacy)+" s WHERE NOT EXISTS (SELECT 1 FROM "+table(schema)
                +" h WHERE h.LEGACY_TABLE='"+legacy+"' AND h.LEGACY_SEQ=s.SEQ) ORDER BY s.SEQ";
    }
    public static String differences(String schema,String legacy) {
        var checks=new ArrayList<String>();
        mapping(legacy).forEach((column,expression)->{
            String left="h."+column;
            if(column.endsWith("_JSON")) checks.add("NOT (("+left+" IS NULL AND ("+expression+") IS NULL) OR ("
                    +left+" IS NOT NULL AND ("+expression+") IS NOT NULL AND NVL(SYS.DBMS_LOB.COMPARE("
                    +left+","+expression+"),1)=0))");
            else checks.add("DECODE("+left+","+expression+",0,1)=1");
        });
        return "SELECT COUNT(*) FROM "+ProfileHistorySql.object(schema,legacy)+" s LEFT JOIN "+table(schema)
                +" h ON h.LEGACY_TABLE='"+legacy+"' AND h.LEGACY_SEQ=s.SEQ WHERE h.SEQ IS NULL OR "+String.join(" OR ",checks);
    }
}
