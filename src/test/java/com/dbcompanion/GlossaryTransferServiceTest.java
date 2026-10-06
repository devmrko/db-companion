package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.model.GlossaryTransfer;
import com.dbcompanion.repository.BusinessGlossaryRepository;
import com.dbcompanion.repository.BusinessGlossaryHistoryRepository;
import com.dbcompanion.service.GlossaryTransferService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GlossaryTransferServiceTest {
    private final List<String> events=new ArrayList<>();
    private final JsonMapper json=JsonMapper.builder().build();
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")||m.getName().equals("rollback"))events.add(m.getName());
            if(m.getName().equals("getAutoCommit"))return true;
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==int.class)return 0;
            return null;
        });
    }};
    private final Repo repo=new Repo();
    private boolean failHistory;
    private final BusinessGlossaryHistoryRepository history=new BusinessGlossaryHistoryRepository(new JdbcTemplate(source),null,json){
        @Override public void require(String owner){}
        @Override public void append(String owner,BusinessGlossary.Term before,BusinessGlossary.Term after){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();events.add("history");
            if(failHistory)throw new IllegalStateException("History unavailable");
        }
    };
    private final GlossaryTransferService service=new GlossaryTransferService(source,repo,history,json);
    private class Repo extends BusinessGlossaryRepository {
        List<BusinessGlossary.Term> terms=List.of();
        Repo(){super(new JdbcTemplate(source),json);}
        @Override public void requireTable(String owner){assertThat(owner).isEqualTo("APP");}
        @Override public List<BusinessGlossary.Term> transferTerms(String owner){return terms;}
        @Override public void lockTransfer(String owner){events.add("lock");}
        @Override public BusinessGlossary.Term save(String owner,String id,long revision,BusinessGlossary.Draft value){
            events.add("save");return term(id==null?"new":id,revision+1,value);
        }
    }
    private BusinessGlossary.Draft draft(String definition){return new BusinessGlossary.Draft("Users",List.of(),definition,"",false);}
    private BusinessGlossary.Term term(String id,long revision,BusinessGlossary.Draft value){return new BusinessGlossary.Term(id,revision,value.term(),value.aliases(),value.definition(),value.criteria(),value.enabled(),"time");}
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","OTHER","LOW","DB"),List.of("APP","OTHER")));return s;}
    private GlossaryTransfer.Preview preview(PoolSession s){return service.preview(s,json.writeValueAsString(new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,1,List.of(draft("new")))));}
    @Test void previewIsReadOnlyAndApplyCommitsContentAndHistoryTogether(){
        try(var s=session()){
            var p=preview(s);assertThat(events).doesNotContain("save","history","lock");events.clear();
            assertThat(service.apply(s,p,new GlossaryTransfer.Apply(p.token(),List.of(0),true))).isEqualTo(1);
            assertThat(events).containsExactly("lock","save","history","commit");
        }
    }
    @Test void stalePreviewAndConcurrentNewTermStopBeforeAnyWrite(){
        try(var s=session()){
            var p=preview(s);repo.terms=List.of(term("created-elsewhere",1,draft("other")));events.clear();
            assertThatThrownBy(()->service.apply(s,p,new GlossaryTransfer.Apply(p.token(),List.of(0),true))).isInstanceOf(RuntimeException.class);
            assertThat(events).containsExactly("lock","rollback");
        }
    }
    @Test void historyFailureRollsBackTermWrite(){
        try(var s=session()){
            var p=preview(s);events.clear();failHistory=true;
            assertThatThrownBy(()->service.apply(s,p,new GlossaryTransfer.Apply(p.token(),List.of(0),true))).isInstanceOf(IllegalStateException.class);
            assertThat(events).containsExactly("lock","save","history","rollback");
        }
    }
    @Test void exportKeepsInactiveTermsAndMalformedOrOversizedInputHasNoWrites(){
        try(var s=session()){
            repo.terms=List.of(term("old",7,draft("definition")));
            assertThat(service.export(s).terms()).containsExactly(draft("definition"));
            for(String value:List.of("null","[]","{}","x".repeat(GlossaryTransfer.MAX_BYTES+1)))
                assertThatThrownBy(()->service.preview(s,value)).isInstanceOf(RuntimeException.class);
            assertThat(events).doesNotContain("lock","save","history");
        }
    }
}
