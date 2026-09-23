package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.TableRowHistorySql.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.TableRowHistory.*;
import com.dbcompanion.repository.FeedbackTrackingRepository;
import com.dbcompanion.repository.FeedbackTrackingRepository.Check;
import com.dbcompanion.service.TableRowHistoryService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TableRowHistoryTest {
    private final Target target=new Target("APP","QA_ROWS",123,List.of(new Column("ID","NUMBER",false),new Column("QUESTION","CLOB",false),
            new Column("DETAIL","JSON",false),new Column("EMBEDDING","VECTOR",false)));
    private State state(String code){return new State(code,"","","READY",123L,target.signature(),TableRowHistorySql.triggerName(target),false,false,code.equals("OFF"),code.equals("ON"),List.of("ID","QUESTION","DETAIL"),List.of("EMBEDDING"));}
    @Test void triggerUsesFullNonVectorJsonAndSameTransactionActorAndTime() {
        var sql=TableRowHistorySql.create(target);
        assertThat(sql).contains("FOR INSERT OR UPDATE OR DELETE ON \"APP\".\"QA_ROWS\"","DISABLE\nCOMPOUND TRIGGER","BEFORE STATEMENT IS","AFTER EACH ROW IS",
                "'TABLE','QA_ROWS','123'","SYS_CONTEXT('USERENV','SESSION_USER')","SYS_EXTRACT_UTC(SYSTIMESTAMP)","RETURNING CLOB",
                "TO_CHAR(:OLD.\"ID\",'TM9'","JSON_SERIALIZE(:NEW.\"DETAIL\" RETURNING CLOB)","NVL(SYS.DBMS_LOB.COMPARE(v_old,v_new),1)<>0")
                .doesNotContain(":OLD.\"EMBEDDING\"",":NEW.\"EMBEDDING\"","VECTOR_SERIALIZE","AUTONOMOUS_TRANSACTION","COMMIT","EXCEPTION","OR REPLACE","SUBSTR");
        assertThat(TableRowHistorySql.snapshot(target,"OLD")).doesNotContain("EMBEDDING","FORMAT JSON").contains("'QUESTION' VALUE :OLD.\"QUESTION\"");
        assertThat(sql).contains("FROM SYS.ALL_TAB_COLS","USER_GENERATED='YES'","n_all<>4 OR n_match<>4","RAISE_APPLICATION_ERROR(-20086");
    }
    @Test void identifiersAndLiteralsAreQuotedAndIdentityIsNotHardcoded() {
        var quoted=new Target("A\"B","T'O",456,List.of(new Column("Q\"'X","VARCHAR2",false),new Column("V","VECTOR",false)));
        assertThat(TableRowHistorySql.create(quoted)).contains("\"A\"\"B\".\"T'O\"","'T''O'","'Q\"''X' VALUE :OLD.\"Q\"\"'X\"");
        assertThat(TableRowHistorySql.triggerName(target)).matches("DBC_RH_[0-9A-F]{22}");
        assertThat(TableRowHistorySql.triggerName(new Target("OTHER",target.table(),123,target.columns()))).isNotEqualTo(TableRowHistorySql.triggerName(target));
        assertThat(TableRowHistorySql.triggerName(new Target("APP",target.table(),124,target.columns()))).isNotEqualTo(TableRowHistorySql.triggerName(target));
    }
    @Test void unsupportedColumnsAndOversizedTriggerDoNotProduceInstallableSql() {
        for(var column:List.of(new Column("X","BLOB",false),new Column("X","LONG",false),new Column("X","XMLTYPE",false),new Column("X","VARCHAR2",true))) {
            var t=new Target("A","T",1,List.of(column,new Column("V","VECTOR",false)));
            assertThat(t.supported()).isFalse();assertThatThrownBy(()->TableRowHistorySql.create(t)).isInstanceOf(IllegalArgumentException.class);
        }
        var many=new ArrayList<Column>();for(int i=0;i<500;i++)many.add(new Column("COL_"+i,"VARCHAR2",false));many.add(new Column("V","VECTOR",false));
        assertThatThrownBy(()->TableRowHistorySql.create(new Target("A","T",1,many))).hasMessageContaining("30,000");
        assertThatThrownBy(()->TableRowHistorySql.snapshot(target,"NEW;DROP")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Column("T","TIMESTAMP(6) WITH TIME ZONE",false).supported()).isTrue();
    }
    @Test void sourceMatchAllowsDictionaryHeaderAndDisableOmissionButRejectsEditsOrColumnChanges() {
        String sql=TableRowHistorySql.create(target),stored=sql.substring(7).replace("\nDISABLE\n","\n")+"\n";
        assertThat(TableRowHistorySql.sourceMatches(target,stored)).isTrue();
        assertThat(TableRowHistorySql.sourceMatches(target,stored.replace("v_old,v_new","v_new,v_old"))).isFalse();
        var changed=new Target("APP","QA_ROWS",123,List.of(new Column("ID","NUMBER",false),new Column("QUESTION","VARCHAR2",false),new Column("V","VECTOR",false)));
        assertThat(TableRowHistorySql.sourceMatches(changed,stored)).isFalse();
        assertThat(TableRowHistorySql.sourceMatches(target,null)).isFalse();
    }
    private List<Check> feedbackChecks(){return FeedbackTrackingSql.expansions().stream().map(e->new Check(e.name(),e.newExpression(),"ENABLED","VALIDATED")).toList();}
    private List<Check> rowChecks(){var checks=new ArrayList<Check>();checks.add(feedbackChecks().get(1));for(var e:TableRowHistorySql.expansions())checks.add(new Check(e.name(),e.newExpression(),"ENABLED","VALIDATED"));return checks;}
    @Test void sharedArchiveAcceptsLegacyFeedbackAndTableSupersetWithoutAcceptingArbitraryRules() {
        FeedbackTrackingRepository.validateChecks(feedbackChecks());assertThat(FeedbackTrackingRepository.tableRowsReady(feedbackChecks())).isFalse();
        FeedbackTrackingRepository.validateChecks(rowChecks());assertThat(FeedbackTrackingRepository.tableRowsReady(rowChecks())).isTrue();
        var partial=new ArrayList<>(feedbackChecks());partial.addAll(rowChecks());FeedbackTrackingRepository.validateChecks(partial);assertThat(FeedbackTrackingRepository.tableRowsReady(partial)).isFalse();
        var bad=new ArrayList<>(rowChecks());bad.set(1,new Check("DBC_AIH_ROW_TYPE_CK","OBJECT_TYPE IN ('TABLE')","ENABLED","VALIDATED"));
        assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(bad)).isInstanceOf(RuntimeException.class);
        var disabled=new ArrayList<>(rowChecks());var c=disabled.get(1);disabled.set(1,new Check(c.name(),c.expression(),"DISABLED","NOT VALIDATED"));
        assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(disabled)).isInstanceOf(RuntimeException.class);
        var missing=new ArrayList<>(rowChecks());missing.removeFirst();assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(missing)).isInstanceOf(RuntimeException.class);
    }
    @Test void scopeAndRequestsRequireOwnerAndFreshTableIdentity() {
        TableRowHistoryService.scope("ADMIN","APP","APP","T",false);
        assertThatThrownBy(()->TableRowHistoryService.scope("ADMIN","APP","APP","T",true)).hasMessageContaining("Owner login");
        assertThatThrownBy(()->TableRowHistoryService.scope("APP","OTHER","APP","T",true)).isInstanceOf(VectorSearch.Failure.class);
        var request=new Request("APP","QA_ROWS",123L,target.signature(),"enable");TableRowHistoryService.sameTarget(request,target);
        assertThatThrownBy(()->TableRowHistoryService.sameTarget(new Request("APP","QA_ROWS",124L,target.signature(),"enable"),target)).hasMessageContaining("Target changed");
        assertThatThrownBy(()->TableRowHistoryService.sameTarget(new Request("APP","QA_ROWS",123L,"old","enable"),target)).hasMessageContaining("Target changed");
        assertThat(TableRowHistoryService.allowed(state("OFF"),"enable")).isTrue();assertThat(TableRowHistoryService.allowed(state("OFF"),"disable")).isFalse();
        assertThat(TableRowHistoryService.allowed(state("ON"),"drop")).isFalse();
    }
    @Test void statusCacheIsScopedAndRefreshExplicitAndErrorsAreNotCached() {
        var s=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));var count=new AtomicInteger();
        var loader=(java.util.function.Supplier<State>)()->{count.incrementAndGet();return state("OFF");};
        s.rowHistoryState("APP","T",false,loader);s.rowHistoryState("APP","T",false,loader);assertThat(count.get()).isEqualTo(1);
        s.rowHistoryState("APP","T",true,loader);s.rowHistoryState("OTHER","T",false,loader);assertThat(count.get()).isEqualTo(3);
        s.forgetRowHistorySchema("APP");s.rowHistoryState("APP","T",false,loader);assertThat(count.get()).isEqualTo(4);
        assertThatThrownBy(()->s.rowHistoryState("APP","T",true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        s.rowHistoryState("APP","T",false,loader);assertThat(count.get()).isEqualTo(5);
    }
    @Test void repositoryKeepsHistoryAndListsOnlyTenWithoutLoadingSnapshots() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/TableRowHistoryRepository.java"));
        assertThat(source).contains("OBJECT_TYPE='TABLE' AND OBJECT_NAME=? AND ENTRY_KIND='ROW_CHANGE'","FETCH NEXT 11 ROWS ONLY","archives.prepare(schema)")
                .doesNotContain("TRUNCATE TABLE","DROP TABLE","DELETE FROM","GRANT ");
        assertThat(source.indexOf(" ENABLE VALIDATE")).isLessThan(source.indexOf(" DROP CONSTRAINT "));
        String page=source.substring(source.indexOf("public Page page("),source.indexOf("public Entry entry("));assertThat(page).doesNotContain("BEFORE_JSON","AFTER_JSON");
    }
}
