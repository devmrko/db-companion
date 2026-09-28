package com.dbcompanion.common.db;

import com.dbcompanion.model.Ontology;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Copy-only Oracle Text DDL and query-literal factory. It never opens a connection or executes SQL. */
public final class OracleTextSql {
    private static final String TABLE="DBC_GLOSSARY_TERM",INDEX="DBC_GLT_CTX",PREFERENCE="DBC_GLT_KO_LEXER";
    private OracleTextSql() {}
    public record InstallPlan(String table,String thesaurus,List<String> requiredPrivileges,List<String> ddl,List<String> warnings) { public InstallPlan { requiredPrivileges=List.copyOf(requiredPrivileges);ddl=List.copyOf(ddl);warnings=List.copyOf(warnings); } }
    public record BoundQuery(String sql,List<Object> bindings) { public BoundQuery { bindings=List.copyOf(bindings); } }

    public static InstallPlan installPlan(String owner) { return installPlan(owner,"DEFAULT"); }
    /** Profile-specific thesaurus prevents a same-spelled alias in another helper profile expanding this scope. */
    public static InstallPlan installPlan(String owner,String profile) {
        text(profile,256,true);String schema=identifier(owner),table=schema+"."+identifier(TABLE),thesaurus=thesaurus(owner,profile),ownerLiteral=literal(owner);
        String createTable="""
                CREATE TABLE %s (
                  TERM_ID VARCHAR2(64 CHAR) NOT NULL,
                  SOURCE_OWNER VARCHAR2(128 CHAR) NOT NULL,
                  SOURCE_TABLE VARCHAR2(128 CHAR) NOT NULL,
                  SOURCE_REVISION NUMBER(10) NOT NULL,
                  PROFILE_REF VARCHAR2(256 CHAR) NOT NULL,
                  SOURCE_KIND VARCHAR2(32 CHAR) NOT NULL,
                  SOURCE_ID VARCHAR2(128 CHAR) NOT NULL,
                  TERM_TEXT VARCHAR2(256 CHAR) NOT NULL,
                  DEFINITION_TEXT CLOB NOT NULL,
                  ALIASES_JSON CLOB NOT NULL,
                  SEARCH_TEXT CLOB NOT NULL,
                  FORMAT_VERSION NUMBER(1) DEFAULT 1 NOT NULL,
                  CREATED_AT TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
                  CONSTRAINT DBC_GLT_V1 CHECK (FORMAT_VERSION = 1),
                  CONSTRAINT DBC_GLT_PK PRIMARY KEY (TERM_ID),
                  CONSTRAINT DBC_GLT_OWNER CHECK (SOURCE_OWNER = %s),
                  CONSTRAINT DBC_GLT_KIND CHECK (SOURCE_KIND IN ('TABLE','COLUMN','VALUE_MAPPING')),
                  CONSTRAINT DBC_GLT_UK UNIQUE (SOURCE_TABLE,SOURCE_REVISION,PROFILE_REF,SOURCE_KIND,SOURCE_ID,TERM_TEXT)
                );
                """.formatted(table,ownerLiteral).strip();
        String preference="""
                BEGIN
                  CTX_DDL.CREATE_PREFERENCE('%s','KOREAN_MORPH_LEXER');
                  CTX_DDL.SET_ATTRIBUTE('%s','COMPOSITE','COMPONENT_WORD');
                END;
                /""".formatted(PREFERENCE,PREFERENCE);
        String context="CREATE INDEX "+schema+"."+identifier(INDEX)+" ON "+table+"(SEARCH_TEXT) INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('LEXER "+PREFERENCE+"');";
        return new InstallPlan(table,thesaurus,List.of("CREATE TABLE in the signed-in owner schema","CREATE INDEX on the owner-bound term table (CREATE ANY INDEX is only needed for another schema)","EXECUTE on CTXSYS.CONTEXT, CTX_DDL and CTX_THES; read access to their owner-scoped dictionary views"),List.of(createTable,preference,context),List.of("Preview does not execute SQL. A separate explicit confirmation is required for installation or synchronization.","Run each CREATE statement through SQLcl with its terminating semicolon; run each PL/SQL block with the slash on a line by itself.","Oracle DDL implicitly commits. Inspect each object after a failure; do not blindly retry a partial script.","Package visibility is not EXECUTE privilege. KOREAN_MORPH_LEXER support and privileges remain unverified until execution.","Synchronization creates immutable CTX_THES dictionaries per profile, source table, revision and approved payload; no existing dictionary is edited or dropped."));
    }

    /** Braces make the complete user term literal; close brace and backslash use Oracle Text's documented escapes. */
    public static String escapeContainsTerm(String term) {
        text(term,256,true);return "{"+term.replace("\\","\\\\").replace("}","}}")+"}";
    }
    /** Read-only Oracle Text query factory. Scope/revision/profile predicates are bound before ranking. */
    public static BoundQuery containsQuery(String owner,String profile,Map<String,Integer> approved,String term,int limit) { return containsQuery(owner,profile,approved,term,limit,false); }
    public static BoundQuery containsQuery(String owner,String profile,Map<String,Integer> approved,String term,int limit,boolean synonyms) {
        text(profile,256,true);if(approved==null||approved.isEmpty()||approved.size()>100||limit<1||limit>100)throw new Ontology.Failure(400,"invalid");String table=identifier(owner)+"."+identifier(TABLE);
        var predicates=new ArrayList<String>();var bindings=new ArrayList<Object>();bindings.add(owner);bindings.add(profile);
        for(var entry:new TreeMap<>(approved).entrySet()){Ontology.name(entry.getKey());if(entry.getValue()==null||entry.getValue()<1)throw new Ontology.Failure(400,"invalid");predicates.add("(SOURCE_TABLE=? AND SOURCE_REVISION=?)");bindings.add(entry.getKey());bindings.add(entry.getValue());}
        String expression=escapeContainsTerm(term);if(synonyms)expression="SYN("+expression+","+thesaurus(owner,profile)+")";bindings.add(expression);bindings.add(limit);
        return new BoundQuery("SELECT TERM_ID,SOURCE_TABLE,SOURCE_REVISION,SOURCE_KIND,SOURCE_ID,TERM_TEXT,DEFINITION_TEXT,ALIASES_JSON,SCORE(1) SCORE FROM "+table+" WHERE SOURCE_OWNER=? AND PROFILE_REF=? AND ("+String.join(" OR ",predicates)+") AND CONTAINS(SEARCH_TEXT, ?, 1)>0 ORDER BY SCORE(1) DESC FETCH FIRST ? ROWS ONLY",bindings);
    }
    /** Generates a source-scoped CTX_THES synonym ring script; callers must still verify current approval before copying. */
    public static String thesaurusSynonyms(String owner,String preferred,List<String> aliases) {
        text(preferred,256,true);if(aliases==null||aliases.isEmpty()||aliases.size()>100)throw new Ontology.Failure(400,"invalid");var unique=new LinkedHashSet<String>();for(String alias:aliases){text(alias,256,true);if(!alias.equals(preferred))unique.add(alias);}if(unique.isEmpty())throw new Ontology.Failure(400,"invalid");String name=thesaurus(owner),term=literal(preferred);var sql=new StringBuilder("BEGIN\n  CTX_THES.CREATE_PHRASE('").append(name).append("',").append(term).append(");\n");for(String alias:unique){String escaped=literal(alias);sql.append("  CTX_THES.CREATE_PHRASE('").append(name).append("',").append(escaped).append(");\n");sql.append("  CTX_THES.CREATE_RELATION('").append(name).append("',").append(term).append(",'SYN',").append(escaped).append(");\n");}return sql.append("END;\n/").toString();
    }
    public static String thesaurus(String owner,String profile) { text(profile,256,true);return "DBC_GLT_"+digest(owner+"\u0000"+profile).substring(0,22); }
    /** SQLcl delimiters are not JDBC SQL; the semicolon inside a PL/SQL block must remain. */
    public static String jdbcStatement(String statement) {
        String value=statement.strip().replaceFirst("(?s)\\R/\\s*$","").strip();
        if(!value.startsWith("BEGIN")&&!value.startsWith("DECLARE")&&value.endsWith(";"))value=value.substring(0,value.length()-1);
        return value;
    }
    private static String thesaurus(String owner) { return thesaurus(owner,"DEFAULT"); }
    private static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static String identifier(String value) { Ontology.name(value);return "\""+value.replace("\"","\"\"")+"\""; }
    private static String literal(String value) { text(value,256,true);return "'"+value.replace("'","''")+"'"; }
    private static void text(String value,int maximum,boolean required) {if(value==null||value.codePointCount(0,value.length())>maximum||value.indexOf(0)>=0||value.codePoints().anyMatch(Character::isISOControl)||(required&&value.isBlank()))throw new Ontology.Failure(400,"invalid");}
}
