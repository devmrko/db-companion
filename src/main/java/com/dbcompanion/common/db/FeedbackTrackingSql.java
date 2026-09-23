package com.dbcompanion.common.db;

import com.dbcompanion.model.AiFeedback;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Explicit installation only; never changes Feedback values or replaces an existing trigger. */
public final class FeedbackTrackingSql {
    private FeedbackTrackingSql() {}
    public record Target(String schema, String profile, String profileId, long tableId) {
        public Target {
            new AiFeedback.Query(schema, profile, "", "", 1);
            AiFeedback.tableName(profile);
            if (schema.isBlank() || profileId == null || !profileId.matches("[0-9]+") || tableId <= 0)
                throw new IllegalArgumentException("Invalid Feedback tracking identity");
        }
    }
    public record Expansion(String name, String oldExpression, String newExpression) {}
    public static List<Expansion> expansions() {
        String types="OBJECT_TYPE IN ('PROFILE','TEAM','AGENT','TASK','TOOL')";
        String kinds="ENTRY_KIND IN ('EDIT','SNAPSHOT','REQUEST','UNCLASSIFIED')";
        String shape="(ENTRY_KIND='EDIT' AND OBJECT_NAME IS NOT NULL AND BEFORE_JSON IS NOT NULL)"
                +" OR (ENTRY_KIND='SNAPSHOT' AND OBJECT_TYPE='PROFILE' AND AFTER_JSON IS NOT NULL)"
                +" OR (ENTRY_KIND IN ('REQUEST','UNCLASSIFIED') AND OBJECT_TYPE='PROFILE' AND PAYLOAD_JSON IS NOT NULL)";
        return List.of(new Expansion("DBC_AIH_FB_TYPE_CK",types,"OBJECT_TYPE IN ('PROFILE','TEAM','AGENT','TASK','TOOL','FEEDBACK')"),
                new Expansion("DBC_AIH_FB_KIND_CK",kinds,"ENTRY_KIND IN ('EDIT','SNAPSHOT','REQUEST','UNCLASSIFIED','ROW_CHANGE')"),
                new Expansion("DBC_AIH_FB_ROW_CK",shape,shape+" OR (ENTRY_KIND='ROW_CHANGE' AND OBJECT_TYPE='FEEDBACK'"
                        +" AND OBJECT_NAME IS NOT NULL AND (BEFORE_JSON IS NOT NULL OR AFTER_JSON IS NOT NULL))"));
    }
    // Oracle preserves our CHECK expressions, sometimes adding enclosing parentheses/identifier quotes.
    // Only our fixed uppercase expressions are accepted; no generic SQL equivalence rewriting.
    public static String condition(String value) {
        if(value==null)return "";
        var compact=new StringBuilder();boolean quoted=false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c=='\'')quoted=!quoted;
            if(quoted||(!Character.isWhitespace(c)&&c!='"'))compact.append(c);
        }
        String result=compact.toString();
        while(result.startsWith("(") && result.endsWith(")") && encloses(result))result=result.substring(1,result.length()-1);
        return result;
    }
    private static boolean encloses(String value) {
        int depth=0; boolean quoted=false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c=='\'')quoted=!quoted;
            if(!quoted){if(c=='(')depth++;if(c==')')depth--;if(depth==0&&i<value.length()-1)return false;}
        }
        return depth==0;
    }
    public static String object(String schema,String name) {return MetadataSql.identifier(schema)+"."+MetadataSql.identifier(name);}
    private static String literal(String text) {return "'"+text.replace("'","''")+"'";}
    public static String triggerName(Target target) {
        try {
            String key=target.schema()+"\0"+target.profile()+"\0"+target.profileId()+"\0"+target.tableId();
            return "DBC_FH_"+HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8))).substring(0,22);
        }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static String create(Target t) {
        return "CREATE TRIGGER "+object(t.schema(),triggerName(t))+"\nAFTER INSERT OR UPDATE OR DELETE ON "
                +object(t.schema(),AiFeedback.tableName(t.profile()))+"\nFOR EACH ROW\nDISABLE\n"
                +"DECLARE\n  v_operation VARCHAR2(1);\n  v_old CLOB;\n  v_new CLOB;\nBEGIN\n"
                +"  -- DB Companion Feedback history v1; profile ID "+t.profileId()+"; table ID "+t.tableId()+"\n"
                +"  IF INSERTING THEN v_operation := 'I'; ELSIF UPDATING THEN v_operation := 'U'; ELSE v_operation := 'D'; END IF;\n"
                +"  IF NOT INSERTING THEN SELECT JSON_OBJECT('content' VALUE :OLD.CONTENT, 'attributes' VALUE "
                +"JSON_SERIALIZE(:OLD.ATTRIBUTES RETURNING CLOB) FORMAT JSON RETURNING CLOB) INTO v_old FROM DUAL; END IF;\n"
                +"  IF NOT DELETING THEN SELECT JSON_OBJECT('content' VALUE :NEW.CONTENT, 'attributes' VALUE "
                +"JSON_SERIALIZE(:NEW.ATTRIBUTES RETURNING CLOB) FORMAT JSON RETURNING CLOB) INTO v_new FROM DUAL; END IF;\n"
                +"  INSERT INTO "+AiHistorySql.table(t.schema())+"\n"
                +"    (OBJECT_TYPE,OBJECT_NAME,OBJECT_ID,ATTRIBUTE_NAME,ACTOR,EVENT_AT,ENTRY_KIND,OUTCOME,SOURCE_KEY,ITEM_NO,BEFORE_JSON,AFTER_JSON)\n"
                +"  VALUES ('FEEDBACK',"+literal(t.profile())+","+literal(t.profileId())+",v_operation,"
                +"SYS_CONTEXT('USERENV','SESSION_USER'),"+AiHistorySql.NOW+",'ROW_CHANGE','RECORDED',RAWTOHEX(SYS_GUID()),0,v_old,v_new);\nEND;";
    }
    public static boolean sourceMatches(Target target,String actual) {
        return actual!=null&&body(create(target)).equals(body(actual));
    }
    private static String body(String sql) {
        String text=sql.replace("\r\n","\n");int first=text.indexOf('\n');
        return (first<0?"":text.substring(first+1)).replaceFirst("\\nDISABLE\\nDECLARE\\n", "\nDECLARE\n").stripTrailing();
    }
    public static String switchSql(Target target,boolean enabled) {
        return "ALTER TRIGGER "+object(target.schema(),triggerName(target))+(enabled?" ENABLE":" DISABLE");
    }
}
