package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.SelectAiInspectionService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class SelectAiInspectionServiceTest {
    static Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true;case "isClosed","isReadOnly" -> false;case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "toString" -> "test-only connection";default -> {if(m.getReturnType()==boolean.class)yield false;if(m.getReturnType()==int.class)yield 0;yield null;}
    });}
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return connection();}};
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","v1");
    String annotations="false";int annotationCalls=0,tableCalls=0,feedbackCalls=0,detailCalls=0;boolean missingFeedback=false,failFeedback=false,failAnnotations=false;
    AiFeedback.Query filter;
    final String rowId="AAABBBCCC000001abc";
    final AiAssistantRepository ai=new AiAssistantRepository(jdbc){@Override public AiAssistant.Profile profile(AiAssistant.Selection p){return profile;}};
    final DatabaseRepository catalog=new DatabaseRepository(jdbc){
        @Override public List<AiProfileAttribute> profileAttributes(String s,boolean own,String n){assertThat(s).isEqualTo("APP");assertThat(own).isTrue();return List.of(new AiProfileAttribute("object_list","[{\"owner\":\"APP\",\"name\":\"T\"}]"),new AiProfileAttribute("annotations",annotations),new AiProfileAttribute("comments","false"));}
        @Override public ObjectMetadata objectMetadata(String owner,String name){tableCalls++;return new ObjectMetadata(name,"TABLE","table comment");}
        @Override public List<ColumnInfo> columns(String owner,String name){return List.of(new ColumnInfo(1,"ID","NUMBER","N","column comment"));}
        @Override public List<AnnotationInfo> annotations(String owner,String name,String type){annotationCalls++;if(failAnnotations)throw new IllegalStateException("secret exception");return List.of(new AnnotationInfo("ID","description","meaning",null,null));}
    };
    final AiFeedbackRepository feedback=new AiFeedbackRepository(jdbc){
        @Override public boolean tableExists(String schema,String profile){assertThat(schema).isEqualTo("APP");assertThat(profile).isEqualTo("P");return !missingFeedback;}
        @Override public AiFeedback.Page page(AiFeedback.Query q){feedbackCalls++;filter=q;if(failFeedback)throw new IllegalStateException("secret exception");return new AiFeedback.Page(List.of(new AiFeedback.Item(rowId,q.search(),"negative","fix")),q.page(),true);}
        @Override public AiFeedback.Detail detail(AiFeedback.Query q,String id){detailCalls++;return new AiFeedback.Detail(q.search(),"negative","response","fix",null,"SELECT original","{}");}
    };
    final SelectAiInspectionService service=new SelectAiInspectionService(source,ai,catalog,feedback);
    PoolSession session(){var session=new PoolSession(new HikariDataSource(),"test",()->{});session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","test","test"),List.of("APP","OTHER")));session.metadata().aiTest().select(profile.selection());session.metadata().selectSchema("OTHER");return session;}
    @Test void existingReadersUseLoginOwnerAndPreserveQuestionSearch(){try(var session=session()){
        var s=service.load(session,"P","샘플게임 표준 AU / 사업 AU");assertThat(filter.search()).isEqualTo(s.question());assertThat(filter.schema()).isEqualTo("APP");
        assertThat(s.tables()).isEmpty();s=service.table(session,s.id(),"APP","T");
        assertThat(s.tables().getFirst().comment()).isEqualTo("table comment");assertThat(s.tables().getFirst().columns().getFirst().comment()).isEqualTo("column comment");
        assertThat(annotationCalls).isZero();assertThat(s.tables().getFirst().annotationStatus()).isEqualTo("DISABLED_OR_UNSET");
        assertThat(service.feedbackDetail(session,s.id(),rowId).feedbackDetails().getFirst().detail().sqlText()).isEqualTo("SELECT original");
        s=service.feedback(session,s.id(),"explicit search",2);assertThat(s.feedback().page()).isEqualTo(2);assertThat(s.feedbackDetails()).isEmpty();
    }}
    @Test void annotationOptionIsHonoredAndItsFailureDoesNotDiscardColumns(){try(var session=session()){
        annotations="TRUE";var s=service.load(session,"P","q");s=service.table(session,s.id(),"APP","T");assertThat(annotationCalls).isEqualTo(1);assertThat(s.tables().getFirst().annotations()).hasSize(1);
        failAnnotations=true;s=service.table(session,s.id(),"APP","T");assertThat(s.tables().getFirst().columns()).hasSize(1);assertThat(s.tables().getFirst().annotationStatus()).startsWith("ERROR").doesNotContain("secret");
    }}
    @Test void wrongProfileObjectRowAndChangedFingerprintAreRejectedBeforeLookups(){try(var session=session()){
        assertThatThrownBy(()->service.load(session,"OTHER","q")).isInstanceOf(AiAssistant.Failure.class);
        var s=service.load(session,"P","q");assertThatThrownBy(()->service.table(session,s.id(),"APP","SECRET")).isInstanceOf(AiAssistant.Failure.class);assertThat(tableCalls).isZero();
        assertThatThrownBy(()->service.feedbackDetail(session,s.id(),"AAABBBCCC000002abc")).isInstanceOf(AiAssistant.Failure.class);assertThat(detailCalls).isZero();
        profile=new AiAssistant.Profile(profile.selection(),"oci","model","changed");
        assertThatThrownBy(()->service.table(session,s.id(),"APP","T")).isInstanceOf(AiAssistant.Failure.class);assertThat(tableCalls).isZero();
    }}
    @Test void noTableAndReadFailureAreDifferentFromEmptySearchResults(){try(var session=session()){
        missingFeedback=true;var missing=service.load(session,"P","q");assertThat(missing.feedback().missingTable()).isTrue();assertThat(missing.feedback().rows()).isNull();
        missingFeedback=false;failFeedback=true;var failed=service.load(session,"P","q");assertThat(failed.feedback().missingTable()).isFalse();assertThat(failed.feedback().error()).isNotNull().doesNotContain("secret");assertThat(failed.feedback().rows()).isNull();
    }}
    @Test void longQuestionIsNotChangedAndDoesNotBecomeAnUnfilteredSearch(){try(var session=session()){
        String question="한".repeat(501);var s=service.load(session,"P",question);assertThat(feedbackCalls).isZero();assertThat(s.question()).isEqualTo(question);assertThat(s.feedback().search()).isEqualTo(question);assertThat(s.feedback().error()).isEqualTo("SEARCH_TOO_LONG");
    }}
}
