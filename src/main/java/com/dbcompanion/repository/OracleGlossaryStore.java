package com.dbcompanion.repository;

import com.dbcompanion.common.db.OracleTextSql;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.OntologyRepository.TextCacheRow;
import com.dbcompanion.repository.OntologyRepository.TextProjection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owner-only, explicitly invoked Oracle Text storage. Never repairs, drops, or adopts foreign objects. */
final class OracleGlossaryStore {
    static final String TABLE="DBC_GLOSSARY_TERM", INDEX="DBC_GLT_CTX", LEXER="DBC_GLT_KO_LEXER";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    OracleGlossaryStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    private static void owner(String schema,String login){Ontology.name(schema);if(!schema.equals(login))throw new Ontology.Failure(403,"ownerRequired");}
    private static String table(String schema){Ontology.name(schema);return "\""+schema.replace("\"","\"\"")+"\".\""+TABLE+"\"";}
    String profileVersion(String schema,String login,String profile){
        owner(schema,login);
        String name=profile.startsWith(schema+".")?profile.substring(schema.length()+1):profile;
        return new AiAssistantRepository(jdbc).profile(new AiAssistant.Selection(schema,name)).version();
    }
    private List<String> objects(String schema,String name){return jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,schema,name);}
    void requireMissing(String schema,String login){
        owner(schema,login);
        if(!objects(schema,TABLE).isEmpty()||!objects(schema,INDEX).isEmpty()
                ||!jdbc.queryForList("SELECT PRE_NAME FROM CTXSYS.CTX_USER_PREFERENCES WHERE PRE_NAME=?",String.class,LEXER).isEmpty()
                ||!jdbc.queryForList("SELECT CONSTRAINT_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND CONSTRAINT_NAME IN ('DBC_GLT_PK','DBC_GLT_UK','DBC_GLT_OWNER','DBC_GLT_KIND','DBC_GLT_V1')",String.class,schema).isEmpty())
            throw new Ontology.Failure(409,"text.collision");
    }
    void requireReady(String schema,String login){
        owner(schema,login);
        if(!objects(schema,TABLE).equals(List.of("TABLE")))throw new Ontology.Failure(409,"text.collision");
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3),schema,TABLE);
        if(!columns.equals(List.of("TERM_ID:VARCHAR2:N","SOURCE_OWNER:VARCHAR2:N","SOURCE_TABLE:VARCHAR2:N","SOURCE_REVISION:NUMBER:N","PROFILE_REF:VARCHAR2:N","SOURCE_KIND:VARCHAR2:N","SOURCE_ID:VARCHAR2:N","TERM_TEXT:VARCHAR2:N","DEFINITION_TEXT:CLOB:N","ALIASES_JSON:CLOB:N","SEARCH_TEXT:CLOB:N","FORMAT_VERSION:NUMBER:N","CREATED_AT:TIMESTAMP(6) WITH TIME ZONE:N")))throw new Ontology.Failure(409,"text.collision");
        var marker=jdbc.queryForList("SELECT SEARCH_CONDITION_VC FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_NAME='DBC_GLT_V1' AND STATUS='ENABLED' AND VALIDATED='VALIDATED'",String.class,schema,TABLE);
        if(marker.size()!=1||!marker.getFirst().replaceAll("[\\s\"()]","").equalsIgnoreCase("FORMAT_VERSION=1"))throw new Ontology.Failure(409,"text.collision");
        var primary=jdbc.queryForList("SELECT k.COLUMN_NAME FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' ORDER BY k.POSITION",String.class,schema,TABLE);
        if(!primary.equals(List.of("TERM_ID")))throw new Ontology.Failure(409,"text.collision");
        Long triggers=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'",Long.class,schema,TABLE);
        Long indexes=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_INDEXES WHERE OWNER=? AND TABLE_OWNER=? AND TABLE_NAME=? AND INDEX_NAME=? AND INDEX_TYPE='DOMAIN' AND ITYP_OWNER='CTXSYS' AND ITYP_NAME='CONTEXT' AND DOMIDX_STATUS='VALID' AND DOMIDX_OPSTATUS='VALID'",Long.class,schema,schema,TABLE,INDEX);
        var indexed=jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_IND_COLUMNS WHERE INDEX_OWNER=? AND INDEX_NAME=? ORDER BY COLUMN_POSITION",String.class,schema,INDEX);
        var lexer=jdbc.queryForList("SELECT PRE_OBJECT FROM CTXSYS.CTX_USER_PREFERENCES WHERE PRE_NAME=? AND PRE_CLASS='LEXER'",String.class,LEXER);
        if(!Objects.equals(triggers,0L)||!Objects.equals(indexes,1L)||!indexed.equals(List.of("SEARCH_TEXT"))||!lexer.equals(List.of("KOREAN_MORPH_LEXER")))throw new Ontology.Failure(409,"text.collision");
    }
    void install(String schema,String login,String profile){
        requireMissing(schema,login);
        for(String sql:OracleTextSql.installPlan(schema,profile).ddl())jdbc.execute(OracleTextSql.jdbcStatement(sql));
        requireReady(schema,login);
    }
    static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private record Dictionary(String name,String marker,List<TextCacheRow> rows,Set<String> phrases) {}
    private List<Dictionary> dictionaries(String schema,String profile,List<TextCacheRow> rows){
        if(rows.isEmpty()||rows.size()>2000)throw new Ontology.Failure(413,"limit");
        var groups=new TreeMap<String,List<TextCacheRow>>();
        for(var row:rows){Ontology.name(row.table());if(row.revision()<1)throw new Ontology.Failure(400,"invalid");groups.computeIfAbsent(row.table(),key->new ArrayList<>()).add(row);}
        if(groups.size()>100)throw new Ontology.Failure(413,"limit");
        var result=new ArrayList<Dictionary>();
        for(var group:groups.values()){
            group.sort(Comparator.comparing(TextCacheRow::id));
            if(group.size()>500||group.stream().map(TextCacheRow::revision).distinct().count()!=1)throw new Ontology.Failure(413,"limit");
            String fingerprint=digest(schema+"\u0000"+profile+"\u0000"+json.writeValueAsString(group));
            String name="DBC_GLT_"+fingerprint.substring(0,22), marker="DBC_COMPLETE_"+fingerprint;
            var phrases=new TreeSet<String>();phrases.add(marker);
            for(var row:group){phrase(row.term());phrases.add(row.term().toUpperCase(Locale.ROOT));for(String alias:aliases(row)){phrase(alias);phrases.add(alias.toUpperCase(Locale.ROOT));}}
            result.add(new Dictionary(name,marker,List.copyOf(group),Set.copyOf(phrases)));
        }
        return List.copyOf(result);
    }
    private static void phrase(String value){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>256||value.codePoints().anyMatch(Character::isISOControl)||value.contains("(")||value.contains(")"))throw new Ontology.Failure(400,"text.phrase");}
    private List<String> aliases(TextCacheRow row){var values=json.readValue(row.aliases(),String[].class);if(values==null||values.length>10)throw new Ontology.Failure(400,"invalid");return Arrays.asList(values);}
    private boolean dictionaryReady(Dictionary dictionary){
        var found=jdbc.queryForList("SELECT THS_NAME FROM CTXSYS.CTX_USER_THESAURI WHERE THS_NAME=?",String.class,dictionary.name());
        if(found.isEmpty())return false;
        var phrases=jdbc.queryForList("SELECT THP_PHRASE FROM CTXSYS.CTX_USER_THES_PHRASES WHERE THP_THESAURUS=?",String.class,dictionary.name());
        if(!found.equals(List.of(dictionary.name()))||!new HashSet<>(phrases).equals(dictionary.phrases()))throw new Ontology.Failure(409,"text.partial");
        return true;
    }
    List<String> preview(String schema,String login,String profile,List<TextCacheRow> rows){
        requireReady(schema,login);
        var statements=new ArrayList<String>();
        for(var dictionary:dictionaries(schema,profile,rows)){
            boolean existing=dictionaryReady(dictionary);
            statements.add((existing?"REUSE verified immutable thesaurus ":"CREATE scoped thesaurus ")+dictionary.name()+" · "+dictionary.rows().getFirst().table()+" revision "+dictionary.rows().getFirst().revision()+" · "+dictionary.rows().size()+" approved terms");
        }
        statements.add("MERGE "+rows.size()+" approved rows into "+table(schema)+"; no DELETE or DROP");
        statements.add("BEGIN CTX_DDL.SYNC_INDEX('"+INDEX+"'); END;");
        return List.copyOf(statements);
    }
    void sync(String schema,String login,String profile,List<TextCacheRow> rows){
        preview(schema,login,profile,rows); // Validate every target before the first write.
        for(var dictionary:dictionaries(schema,profile,rows)){
            if(dictionaryReady(dictionary))continue;
            jdbc.update("BEGIN CTX_THES.CREATE_THESAURUS(?, FALSE); END;",dictionary.name());
            for(String phrase:dictionary.phrases().stream().filter(p->!p.equals(dictionary.marker())).sorted().toList())
                jdbc.update("BEGIN CTX_THES.CREATE_PHRASE(?,?); END;",dictionary.name(),phrase);
            var relations=new HashSet<String>();
            for(var row:dictionary.rows()){
                for(String alias:aliases(row)){
                    String term=row.term().toUpperCase(Locale.ROOT), synonym=alias.toUpperCase(Locale.ROOT);
                    String edge=term.compareTo(synonym)<0?term+"\u0000"+synonym:synonym+"\u0000"+term;
                    if(!term.equals(synonym)&&relations.add(edge))jdbc.update("BEGIN CTX_THES.CREATE_RELATION(?,?,'SYN',?); END;",dictionary.name(),synonym,term);
                }
            }
            // Completion marker is last. An existing incomplete dictionary is never resumed automatically.
            jdbc.update("BEGIN CTX_THES.CREATE_PHRASE(?,?); END;",dictionary.name(),dictionary.marker());
            if(!dictionaryReady(dictionary))throw new Ontology.Failure(409,"text.partial");
        }
        for(var row:rows)jdbc.update("MERGE INTO "+table(schema)+" d USING (SELECT ? TERM_ID FROM DUAL) s ON (d.TERM_ID=s.TERM_ID) WHEN MATCHED THEN UPDATE SET TERM_TEXT=?,DEFINITION_TEXT=COALESCE(TO_CLOB(?),EMPTY_CLOB()),ALIASES_JSON=?,SEARCH_TEXT=? WHEN NOT MATCHED THEN INSERT (TERM_ID,SOURCE_OWNER,SOURCE_TABLE,SOURCE_REVISION,PROFILE_REF,SOURCE_KIND,SOURCE_ID,TERM_TEXT,DEFINITION_TEXT,ALIASES_JSON,SEARCH_TEXT) VALUES (?,?,?,?,?,?,?,?,COALESCE(TO_CLOB(?),EMPTY_CLOB()),?,?)",row.id(),row.term(),row.definition(),row.aliases(),row.searchText(),row.id(),schema,row.table(),row.revision(),profile,row.kind(),row.sourceId(),row.term(),row.definition(),row.aliases(),row.searchText());
        jdbc.execute("BEGIN CTX_DDL.SYNC_INDEX('"+INDEX+"'); END;");
    }
    List<TextProjection> search(String schema,String login,String profile,Map<String,Integer> approved,String question,int limit,List<TextCacheRow> current){
        requireReady(schema,login);var results=new ArrayList<TextProjection>();
        for(var dictionary:dictionaries(schema,profile,current)){
            if(!dictionaryReady(dictionary))throw new Ontology.Failure(409,"text.unconfirmed");
            var rows=dictionary.rows();var first=rows.getFirst();
            var base=OracleTextSql.containsQuery(schema,profile,Map.of(first.table(),first.revision()),question,limit);
            if(!Objects.equals(approved.get(first.table()),first.revision()))throw new Ontology.Failure(409,"stale");
            var args=new ArrayList<>(base.bindings());args.set(args.size()-2,"SYN("+OracleTextSql.escapeContainsTerm(question)+","+dictionary.name()+")");
            args.removeLast();rows.forEach(row->args.add(row.id()));args.add(limit);
            String sql=base.sql().replace(" ORDER BY"," AND TERM_ID IN ("+String.join(",",Collections.nCopies(rows.size(),"?"))+") ORDER BY");
            results.addAll(jdbc.query(sql,(r,n)->new TextProjection(r.getString(2),r.getInt(3),r.getString(4),r.getString(5),null,null,null,r.getInt(9)),args.toArray()));
        }
        return results.stream().sorted(Comparator.comparingInt(TextProjection::score).reversed().thenComparing(TextProjection::table).thenComparing(TextProjection::sourceId)).limit(limit).toList();
    }
}
