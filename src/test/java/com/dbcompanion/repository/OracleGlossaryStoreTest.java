package com.dbcompanion.repository;

import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.OntologyRepository.TextCacheRow;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic JDBC only: verifies scope and write sequencing without installing Oracle Text. */
class OracleGlossaryStoreTest {
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    static final class FakeJdbc extends JdbcTemplate {
        final List<String> writes=new ArrayList<>();
        final Map<String,Set<String>> dictionaries=new LinkedHashMap<>();
        final List<List<Object>> searches=new ArrayList<>();
        boolean ready=true,trigger,invalidIndex,invalidMarker;
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args){
            List<String> values;
            if(sql.contains("ALL_OBJECTS"))values=ready&&args[1].equals("DBC_GLOSSARY_TERM")?List.of("TABLE"):List.of();
            else if(sql.contains("SEARCH_CONDITION_VC"))values=List.of(invalidMarker?"FORMAT_VERSION=2":"FORMAT_VERSION = 1");
            else if(sql.contains("ALL_CONS_COLUMNS"))values=List.of("TERM_ID");
            else if(sql.contains("ALL_IND_COLUMNS"))values=List.of("SEARCH_TEXT");
            else if(sql.contains("PRE_OBJECT"))values=List.of("KOREAN_MORPH_LEXER");
            else if(sql.contains("CTX_USER_PREFERENCES"))values=ready?List.of("DBC_GLT_KO_LEXER"):List.of();
            else if(sql.contains("CTX_USER_THESAURI"))values=dictionaries.containsKey(args[0])?List.of((String)args[0]):List.of();
            else if(sql.contains("CTX_USER_THES_PHRASES"))values=new ArrayList<>(dictionaries.getOrDefault(args[0],Set.of()));
            else if(sql.contains("ALL_CONSTRAINTS"))values=List.of();
            else throw new AssertionError(sql);
            return values.stream().map(type::cast).toList();
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args){
            long count=sql.contains("ALL_TRIGGERS")?(trigger?1:0):(invalidIndex?0:1);
            return type.cast(count);
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            if(sql.contains("ALL_TAB_COLUMNS")){
                var columns=List.of("TERM_ID:VARCHAR2:N","SOURCE_OWNER:VARCHAR2:N","SOURCE_TABLE:VARCHAR2:N","SOURCE_REVISION:NUMBER:N","PROFILE_REF:VARCHAR2:N","SOURCE_KIND:VARCHAR2:N","SOURCE_ID:VARCHAR2:N","TERM_TEXT:VARCHAR2:N","DEFINITION_TEXT:CLOB:N","ALIASES_JSON:CLOB:N","SEARCH_TEXT:CLOB:N","FORMAT_VERSION:NUMBER:N","CREATED_AT:TIMESTAMP(6) WITH TIME ZONE:N");
                var result=new ArrayList<T>();
                for(String column:columns){var parts=column.split(":");var rs=proxy(java.sql.ResultSet.class,(p,m,a)->m.getName().equals("getString")?parts[(Integer)a[0]-1]:empty(m.getReturnType()));
                    try{result.add(mapper.mapRow(rs,result.size()));}catch(java.sql.SQLException ex){throw new AssertionError(ex);}}
                return result;
            }
            assertThat(sql).contains("CONTAINS(SEARCH_TEXT, ?, 1)","TERM_ID IN (", "SOURCE_TABLE=? AND SOURCE_REVISION=?");
            searches.add(List.of(args));return List.of();
        }
        @Override public int update(String sql,Object... args){
            writes.add(sql+" | "+Arrays.toString(args));
            if(sql.contains("CREATE_THESAURUS"))dictionaries.put((String)args[0],new LinkedHashSet<>());
            if(sql.contains("CREATE_PHRASE")){assertThat(sql).startsWith("BEGIN").endsWith("END;");assertThat(dictionaries.get(args[0]).add((String)args[1])).isTrue();}
            return 1;
        }
        @Override public void execute(String sql){writes.add(sql);}
    }
    final FakeJdbc jdbc=new FakeJdbc();
    final OracleGlossaryStore store=new OracleGlossaryStore(jdbc,new JsonMapper());
    TextCacheRow term(String table,int revision,String term,String aliases){return new TextCacheRow(table+"-"+revision+"-"+term,table,revision,"TABLE",table,term,"definition",aliases,term+" definition");}
    @Test void refusesWrongOwnerAndExistingObjectsBeforeAnyWrite(){
        assertThatThrownBy(()->store.install("APP","OTHER","APP.P")).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->store.install("APP","APP","APP.P")).isInstanceOf(Ontology.Failure.class);
        assertThat(jdbc.writes).isEmpty();
    }
    @Test void refusesUnexpectedMarkerTriggerOrIndexBeforeSynchronization(){
        var rows=List.of(term("T",1,"term","[]"));jdbc.invalidMarker=true;
        assertThatThrownBy(()->store.sync("APP","APP","APP.P",rows)).isInstanceOf(Ontology.Failure.class);
        jdbc.invalidMarker=false;jdbc.trigger=true;
        assertThatThrownBy(()->store.sync("APP","APP","APP.P",rows)).isInstanceOf(Ontology.Failure.class);
        jdbc.trigger=false;jdbc.invalidIndex=true;
        assertThatThrownBy(()->store.sync("APP","APP","APP.P",rows)).isInstanceOf(Ontology.Failure.class);
        assertThat(jdbc.writes).isEmpty();
    }
    @Test void phrasesAreUniqueCompletionIsLastAndRepeatedSyncReusesDictionary(){
        var rows=List.of(term("T",1,"payer","[\"buyer\"]"),term("T",1,"buyer","[\"payer\"]"));
        store.sync("APP","APP","APP.P",rows);
        assertThat(jdbc.dictionaries).hasSize(1);
        assertThat(jdbc.writes.stream().filter(s->s.contains("CREATE_RELATION")).count()).isEqualTo(1);
        var phrases=jdbc.writes.stream().filter(s->s.contains("CREATE_PHRASE")).toList();
        assertThat(phrases).hasSize(3);assertThat(phrases.getLast()).contains("DBC_COMPLETE_");
        jdbc.writes.clear();store.sync("APP","APP","APP.P",rows);
        assertThat(jdbc.writes).noneMatch(s->s.contains("CREATE_THESAURUS")||s.contains("CREATE_PHRASE"));
        assertThat(jdbc.writes.getLast()).isEqualTo("BEGIN CTX_DDL.SYNC_INDEX('DBC_GLT_CTX'); END;");
    }
    @Test void profileTableRevisionAndPayloadEachProduceIndependentThesaurus(){
        store.sync("APP","APP","APP.P",List.of(term("T",1,"term","[]")));
        store.sync("APP","APP","APP.Q",List.of(term("T",1,"term","[]")));
        store.sync("APP","APP","APP.P",List.of(term("U",1,"term","[]")));
        store.sync("APP","APP","APP.P",List.of(term("T",2,"term","[]")));
        store.sync("APP","APP","APP.P",List.of(term("T",1,"term","[\"new alias\"]")));
        assertThat(jdbc.dictionaries).hasSize(5);
    }
    @Test void partialDictionaryIsNotResumedOrRepaired(){
        var rows=List.of(term("T",1,"term","[]"));store.sync("APP","APP","APP.P",rows);
        jdbc.dictionaries.values().iterator().next().removeIf(s->s.startsWith("DBC_COMPLETE_"));jdbc.writes.clear();
        assertThatThrownBy(()->store.sync("APP","APP","APP.P",rows)).isInstanceOf(Ontology.Failure.class);
        assertThat(jdbc.writes).isEmpty();
    }
    @Test void searchBindsScopeCurrentIdsAndSynonymDictionaryWithoutWrites(){
        var rows=List.of(term("T",1,"term","[\"alias\"]"));store.sync("APP","APP","APP.P",rows);jdbc.writes.clear();
        store.search("APP","APP","APP.P",Map.of("T",1),"alias",20,rows);
        assertThat(jdbc.writes).isEmpty();assertThat(jdbc.searches).singleElement().satisfies(args->{
            assertThat(args).contains("APP","APP.P","T",1,"T-1-term",20);
            assertThat(args.get(4).toString()).matches("SYN\\(\\{alias},DBC_GLT_[A-F0-9]{22}\\)");
        });
    }
}
