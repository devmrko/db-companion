package com.dbcompanion;

import com.dbcompanion.model.CatalogColumnProbe;
import com.dbcompanion.model.CatalogColumnProbe.*;
import com.dbcompanion.model.CatalogOperations.Grid;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogColumnProbeTest {
    private final Target target=new Target("APP","CAT","RemoteOwner","lower_table","SAFE.LINK");
    private final MountedCatalogsRepository.Target api=new MountedCatalogsRepository.Target("ORACLE_OWNER","DBMS_CATALOG");
    @Test void targetIsFixedAndLinkCannotInjectSql(){
        assertThat(target.allowed("APP")).isTrue();assertThat(target.allowed("ADMIN")).isFalse();assertThat(target.allowed(null)).isFalse();
        for(String link:List.of("L\" WHERE 1=1 --","L@X","A B","A..B","_BAD"))
            assertThatThrownBy(()->new Target("APP","CAT","S","T",link)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Target("APP","CAT","S","T\n","L")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void apiVariantsNeverRemoveScopeOrChangeIdentifierCase(){
        var normal=CatalogColumnProbeRepository.sql(Step.PARENT_TYPE,api,target);
        assertThat(normal).contains("schema_name => ?, table_name => ?, parent_type => 'TABLE', result_limit => 101");
        assertThat(CatalogColumnProbeRepository.binds(Step.PARENT_TYPE,target)).containsExactly("CAT","RemoteOwner","lower_table");
        var quoted=CatalogColumnProbeRepository.sql(Step.QUOTED_NAMES,api,target);
        assertThat(quoted).contains("schema_name => ?, table_name => ?, result_limit => 101").doesNotContain("parent_type =>");
        assertThat(CatalogColumnProbeRepository.binds(Step.QUOTED_NAMES,target)).containsExactly("CAT","\"RemoteOwner\"","\"lower_table\"");
        assertThat(CatalogColumnProbe.quoted("a\"b")).isEqualTo("\"a\"\"b\"");
    }
    @Test void linkProbeReadsOnlyExactTableDictionaryAndAllValuesAreBound(){
        var sql=CatalogColumnProbeRepository.sql(Step.LINK_DICTIONARY,api,target);
        assertThat(sql).contains("FROM ALL_TAB_COLUMNS@\"SAFE.LINK\" WHERE OWNER = ? AND TABLE_NAME = ? AND ROWNUM <= 101")
            .doesNotContain("SELECT *","lower_table","RemoteOwner","CREATE ","GRANT ","UPDATE ","DELETE ","INSERT ","PASSWORD");
        assertThat(CatalogColumnProbeRepository.binds(Step.LINK_DICTIONARY,target)).containsExactly("RemoteOwner","lower_table");
        assertThat(CatalogColumnProbeRepository.FIELDS).containsExactly("OWNER","TABLE_NAME","COLUMN_NAME","COLUMN_ID","DATA_TYPE","DATA_LENGTH","DATA_PRECISION","DATA_SCALE","NULLABLE");
    }
    @Test void bothSuccessfulAndFailedStepsAreRunAtMostOnce() throws Exception {
        var state=new State();var calls=new AtomicInteger();
        var error=new Result(Step.PARENT_TYPE,Instant.EPOCH,"sql",List.of(),null,"ORA-00942");
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(3)){
            var tasks=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<6;i++)tasks.add(executor.submit(()->state.once(Step.PARENT_TYPE,()->{calls.incrementAndGet();return error;})));
            for(var task:tasks)task.get();
        }
        assertThat(calls).hasValue(1);assertThat(state.results()).containsExactly(error);
        assertThat(state.once(Step.PARENT_TYPE,()->{throw new AssertionError("Must not retry");})).isSameAs(error);
        var empty=new Result(Step.QUOTED_NAMES,Instant.EPOCH,"sql",List.of(),new Grid(List.of("COLUMN_NAME"),List.of()),"");
        state.once(Step.QUOTED_NAMES,()->empty);assertThat(state.results()).hasSize(2);
    }
    @Test void commentProbesOnlyReadBoundedMetadataWithoutAssumingNativeSchema(){
        var dictionary=CatalogColumnProbeRepository.sql(Step.LINK_COMMENTS,api,target);
        assertThat(dictionary).contains("ALL_COL_COMMENTS@\"SAFE.LINK\" WHERE OWNER = ? AND TABLE_NAME = ? AND ROWNUM <= 101");
        for(Step step:List.of(Step.MYSQL_COLUMNS,Step.MYSQL_TABLES)){
            var sql=CatalogColumnProbeRepository.sql(step,api,target);
            assertThat(sql).contains("\"information_schema\".","@\"SAFE.LINK\" WHERE \"TABLE_NAME\" = ? AND ROWNUM <= 101","ORDER BY \"TABLE_SCHEMA\"")
                .doesNotContain("RemoteOwner","lower_table","DATABASE()","SELECT *","CREATE ","GRANT ","INSERT ","UPDATE ","DELETE ");
            assertThat(CatalogColumnProbeRepository.binds(step,target)).containsExactly("lower_table");
        }
        assertThat(CatalogColumnProbeRepository.fields(Step.MYSQL_COLUMNS)).contains("TABLE_SCHEMA","COLUMN_COMMENT","COLUMN_TYPE");
        assertThat(CatalogColumnProbeRepository.fields(Step.MYSQL_TABLES)).containsExactly("TABLE_SCHEMA","TABLE_NAME","TABLE_COMMENT");
        var quotedTable=new Target("APP","CAT","S","a' OR 1=1--","L");
        assertThat(CatalogColumnProbeRepository.sql(Step.MYSQL_COLUMNS,api,quotedTable)).doesNotContain(quotedTable.table());
        assertThat(CatalogColumnProbeRepository.binds(Step.MYSQL_COLUMNS,quotedTable)).containsExactly(quotedTable.table());
    }
    @Test void resultFragmentEscapesMetadataAndDistinguishesEmptyFromFailure(){
        var resolver=new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new org.thymeleaf.spring6.SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var context=new org.thymeleaf.context.Context();
        context.setVariable("results",List.of(new Result(Step.PARENT_TYPE,Instant.EPOCH,"SELECT <text>",List.of("S","T"),
            new Grid(List.of("COLUMN_NAME"),List.of(Map.of("COLUMN_NAME","<script>bad</script>"))),""),
            new Result(Step.QUOTED_NAMES,Instant.EPOCH,"sql",List.of(),null,"ORA-00942")));
        var html=engine.process("catalog-column-probe",Set.of("results"),context);
        assertThat(html).contains("Rows: 1","ORA-00942","&lt;script&gt;").doesNotContain("<script>","th:text");
    }
    @Test void passthroughBindsOneFixedSelectAndClosesTheRemoteCursorOnBothPaths(){
        var sql=CatalogColumnProbeRepository.sql(Step.MYSQL_PASSTHROUGH,api,target);
        assertThat(sql).contains("selected_table VARCHAR2(128) := ?", "OPEN_CURSOR@\"SAFE.LINK\"", "BIND_VARIABLE@\"SAFE.LINK\"(c,1,selected_table)",
            "WHERE c.TABLE_NAME = ?", "t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME", "LIMIT 101", "items.get_size()>=100",
            "WHEN NO_DATA_FOUND THEN EXIT", "? := items.to_clob()", "size_chars>1000000", "LENGTH(v)>8192")
            .doesNotContain("lower_table", "RemoteOwner", "EXECUTE_IMMEDIATE", "EXECUTE_NON_QUERY", "COMMIT", "ROLLBACK", "CREATE ", "GRANT ", "SELECT *");
        assertThat(sql.split("CLOSE_CURSOR",-1)).hasSize(3);
        assertThat(sql.split("GET_VALUE",-1)).hasSize(9);
        assertThat(CatalogColumnProbeRepository.binds(Step.MYSQL_PASSTHROUGH,target)).containsExactly("lower_table");
    }
    @Test void nativeCommentParserKeepsNullsUnicodeAndExtraSeparateAndRejectsUnexpectedData(){
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var values=new LinkedHashMap<String,String>();
        CatalogColumnProbeRepository.fields(Step.MYSQL_PASSTHROUGH).forEach(field->values.put(field,null));
        values.put("COLUMN_NAME","loaded_at");values.put("COLUMN_COMMENT","적재 일시 <text>");
        values.put("EXTRA","DEFAULT_GENERATED on update CURRENT_TIMESTAMP");
        var grid=CatalogColumnProbeRepository.parseNativeComments(mapper.writeValueAsString(List.of(values)),mapper);
        assertThat(grid.rows().getFirst()).containsEntry("TABLE_COMMENT",null).containsEntry("COLUMN_COMMENT","적재 일시 <text>")
            .containsEntry("EXTRA","DEFAULT_GENERATED on update CURRENT_TIMESTAMP");
        assertThat(CatalogColumnProbeRepository.parseNativeComments("[]",mapper).rows()).isEmpty();
        assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments("{}",mapper)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments("[{}]",mapper)).isInstanceOf(IllegalStateException.class);
        values.put("COLUMN_COMMENT","x".repeat(8193));
        assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments(mapper.writeValueAsString(List.of(values)),mapper)).isInstanceOf(IllegalStateException.class);
        values.put("COLUMN_COMMENT",null);values.put("UNEXPECTED","not allowed");
        assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments(mapper.writeValueAsString(List.of(values)),mapper)).isInstanceOf(IllegalStateException.class);
        values.remove("UNEXPECTED");
        assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments(mapper.writeValueAsString(Collections.nCopies(101,values)),mapper)).isInstanceOf(IllegalStateException.class);
    }
    @Test void errorsNeverBecomeAnEmptySuccessfulResultOrExposeStatementDumps(){
        assertThat(com.dbcompanion.service.CatalogColumnProbeService.error(new IllegalArgumentException("private data")))
            .isEqualTo("IllegalArgumentException");
        var wrapped=new IllegalStateException("SQL and binds must not leak",new java.sql.SQLException("ORA-00942: table or view does not exist\nSQL: private statement"));
        assertThat(com.dbcompanion.service.CatalogColumnProbeService.error(wrapped)).isEqualTo("ORA-00942: table or view does not exist");
    }
    @Test void commentLengthsAreComputedInMysqlWithoutFetchingRawCommentOrTypeText(){
        var sql=CatalogColumnProbeRepository.sql(Step.MYSQL_COMMENT_LENGTHS,api,target);
        assertThat(sql).contains("CAST(COALESCE(CHAR_LENGTH(c.COLUMN_COMMENT),-1) AS CHAR(20))",
            "CAST(COALESCE(CHAR_LENGTH(t.TABLE_COMMENT),-1) AS CHAR(20))", "WHERE c.TABLE_NAME = ?", "LIMIT 101",
            "BIND_VARIABLE@\"SAFE.LINK\"(c,1,selected_table)")
            .doesNotContain("c.COLUMN_TYPE", "c.EXTRA", "lower_table", "RemoteOwner", "UPDATE ", "CREATE ", "GRANT ");
        assertThat(CatalogColumnProbeRepository.binds(Step.MYSQL_COMMENT_LENGTHS,target)).containsExactly("lower_table");
        assertThat(sql.split("GET_VALUE",-1)).hasSize(6);
        assertThat(sql.split("CLOSE_CURSOR",-1)).hasSize(3);
    }
    @Test void commentLengthResultsDistinguishEmptyFromPresentAndNullAndRejectInvalidValues(){
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String length:List.of("0","23","-1")){
            var value=Map.of("TABLE_SCHEMA","S","TABLE_NAME","T","COLUMN_NAME","C","COLUMN_COMMENT_CHARS",length,"TABLE_COMMENT_CHARS","0");
            assertThat(CatalogColumnProbeRepository.parseNativeComments(Step.MYSQL_COMMENT_LENGTHS,mapper.writeValueAsString(List.of(value)),mapper).rows().getFirst())
                .containsEntry("COLUMN_COMMENT_CHARS",length).containsEntry("TABLE_COMMENT_CHARS","0");
        }
        for(String length:List.of("", "-2", "d", "0.0", "01", " 0")){
            var value=Map.of("TABLE_SCHEMA","S","TABLE_NAME","T","COLUMN_NAME","C","COLUMN_COMMENT_CHARS",length,"TABLE_COMMENT_CHARS","0");
            assertThatThrownBy(()->CatalogColumnProbeRepository.parseNativeComments(Step.MYSQL_COMMENT_LENGTHS,mapper.writeValueAsString(List.of(value)),mapper))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
