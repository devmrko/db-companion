package com.dbcompanion.common.db;

import com.dbcompanion.model.VectorSearch;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** Generated only from freshly inspected dictionary columns. Never serializes VECTOR values. */
public final class TableRowHistorySql {
    private TableRowHistorySql() {}
    public record Column(String name,String type,boolean virtual) {
        public Column {VectorSearch.name(name);Objects.requireNonNull(type);}
        public boolean vector(){return type.equals("VECTOR");}
        public boolean supported(){return !virtual&&(vector()||Set.of("CHAR","VARCHAR2","NCHAR","NVARCHAR2","CLOB","NCLOB","JSON",
                "NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE","DATE","RAW","ROWID","UROWID","BOOLEAN").contains(type)
                ||(!virtual&&type.matches("TIMESTAMP(\\([0-9]+\\))?( WITH (LOCAL )?TIME ZONE)?")));}
    }
    public record Target(String schema,String table,long id,List<Column> columns) {
        public Target {
            VectorSearch.name(schema);VectorSearch.name(table);columns=List.copyOf(columns);
            if(id<=0||columns.isEmpty()||columns.stream().map(Column::name).distinct().count()!=columns.size())throw new IllegalArgumentException("Invalid table identity");
        }
        public String signature(){return hash(schema+"\0"+table+"\0"+id+"\0"+columns.stream()
                .map(c->c.name().length()+":"+c.name()+":"+c.type()+":"+c.virtual()).collect(Collectors.joining("\0")));}
        public List<String> unsupported(){return columns.stream().filter(c->!c.supported()).map(c->c.name()+" ("+c.type()+(c.virtual()?", VIRTUAL":"")+")").toList();}
        public boolean supported(){return columns.stream().anyMatch(Column::vector)&&columns.stream().anyMatch(c->!c.vector())&&unsupported().isEmpty();}
    }
    public static String hash(String text) {
        try{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static String triggerName(Target t){return "DBC_RH_"+hash(t.schema()+"\0"+t.table()+"\0"+t.id()).substring(0,22);}
    public static String literal(String value){return "'"+value.replace("'","''")+"'";}
    public static String object(String schema,String name){return VectorSearch.quote(schema)+"."+VectorSearch.quote(name);}
    public static List<FeedbackTrackingSql.Expansion> expansions() {
        var old=FeedbackTrackingSql.expansions();
        return List.of(new FeedbackTrackingSql.Expansion("DBC_AIH_ROW_TYPE_CK",old.get(0).newExpression(),
                "OBJECT_TYPE IN ('PROFILE','TEAM','AGENT','TASK','TOOL','FEEDBACK','TABLE')"),
                new FeedbackTrackingSql.Expansion("DBC_AIH_ROW_DATA_CK",old.get(2).newExpression(),old.get(2).newExpression()
                        +" OR (ENTRY_KIND='ROW_CHANGE' AND OBJECT_TYPE='TABLE' AND OBJECT_NAME IS NOT NULL AND (BEFORE_JSON IS NOT NULL OR AFTER_JSON IS NOT NULL))"));
    }
    public static String snapshot(Target t,String row) {
        if(!Set.of("OLD","NEW").contains(row)||!t.supported())throw new IllegalArgumentException("Unsupported snapshot");
        return "JSON_OBJECT("+t.columns().stream().filter(c->!c.vector()).map(c->{
            String ref=":"+row+"."+VectorSearch.quote(c.name());
            String value=switch(c.type()) {
                // Keep JSON column text intact, including numbers beyond JavaScript's safe integer range.
                case "JSON" -> "JSON_SERIALIZE("+ref+" RETURNING CLOB)";
                case "NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE" -> "TO_CHAR("+ref+",'TM9','NLS_NUMERIC_CHARACTERS=''.,''')";
                case "RAW" -> "RAWTOHEX("+ref+")";
                case "ROWID","UROWID" -> "TO_CHAR("+ref+")";
                default -> ref;
            };
            return literal(c.name())+" VALUE "+value;
        }).collect(Collectors.joining(", "))+" NULL ON NULL RETURNING CLOB)";
    }
    public static String create(Target t) {
        if(!t.supported())throw new IllegalArgumentException("Unsupported history columns");
        String shape=t.columns().stream().map(c->"(COLUMN_NAME="+literal(c.name())+" AND DATA_TYPE="+literal(c.type())
                +" AND VIRTUAL_COLUMN='NO')").collect(Collectors.joining(" OR "));
        String sql="CREATE TRIGGER "+object(t.schema(),triggerName(t))+"\nFOR INSERT OR UPDATE OR DELETE ON "+object(t.schema(),t.table())
                +"\nDISABLE\nCOMPOUND TRIGGER\n"
                +"  -- DB Companion table row history v1; "+t.signature()+"\n"
                +"  BEFORE STATEMENT IS\n    n_all PLS_INTEGER; n_match PLS_INTEGER;\n  BEGIN\n"
                +"    SELECT COUNT(*), NVL(SUM(CASE WHEN "+shape+" THEN 1 ELSE 0 END),0) INTO n_all,n_match\n"
                +"      FROM SYS.ALL_TAB_COLS WHERE OWNER="+literal(t.schema())+" AND TABLE_NAME="+literal(t.table())+" AND USER_GENERATED='YES';\n"
                +"    IF n_all<>"+t.columns().size()+" OR n_match<>"+t.columns().size()+" THEN\n"
                +"      RAISE_APPLICATION_ERROR(-20086,'DB Companion history: table columns changed; refresh tracking status');\n    END IF;\n  END BEFORE STATEMENT;\n"
                +"  AFTER EACH ROW IS\n    v_old CLOB; v_new CLOB; v_operation VARCHAR2(1);\n  BEGIN\n"
                +"    IF INSERTING THEN v_operation:='I'; ELSIF UPDATING THEN v_operation:='U'; ELSE v_operation:='D'; END IF;\n"
                +"    IF NOT INSERTING THEN SELECT "+snapshot(t,"OLD")+" INTO v_old FROM SYS.DUAL; END IF;\n"
                +"    IF NOT DELETING THEN SELECT "+snapshot(t,"NEW")+" INTO v_new FROM SYS.DUAL; END IF;\n"
                +"    IF NOT UPDATING OR NVL(SYS.DBMS_LOB.COMPARE(v_old,v_new),1)<>0 THEN\n"
                +"      INSERT INTO "+AiHistorySql.table(t.schema())+"\n"
                +"        (OBJECT_TYPE,OBJECT_NAME,OBJECT_ID,ATTRIBUTE_NAME,ACTOR,EVENT_AT,ENTRY_KIND,OUTCOME,SOURCE_KEY,ITEM_NO,BEFORE_JSON,AFTER_JSON)\n"
                +"      VALUES ('TABLE',"+literal(t.table())+","+literal(Long.toString(t.id()))+",v_operation,SYS_CONTEXT('USERENV','SESSION_USER'),"
                +AiHistorySql.NOW+",'ROW_CHANGE','RECORDED',RAWTOHEX(SYS_GUID()),0,v_old,v_new);\n"
                +"    END IF;\n  END AFTER EACH ROW;\nEND;";
        if(sql.getBytes(StandardCharsets.UTF_8).length>30_000)throw new IllegalArgumentException("History trigger source exceeds 30,000 bytes");
        return sql;
    }
    public static boolean sourceMatches(Target target,String actual){return actual!=null&&body(create(target)).equals(body(actual));}
    private static String body(String sql){String s=sql.replace("\r\n","\n");int p=s.indexOf('\n');return (p<0?"":s.substring(p+1)).replace("\nDISABLE\nCOMPOUND TRIGGER\n","\nCOMPOUND TRIGGER\n").stripTrailing();}
    public static String switchSql(Target t,boolean enabled){return "ALTER TRIGGER "+object(t.schema(),triggerName(t))+(enabled?" ENABLE":" DISABLE");}
}
