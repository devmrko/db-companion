package com.dbcompanion.service;

import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiFeedback;
import com.dbcompanion.model.AiProfile;
import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.model.CredentialCatalog.Catalog;
import com.dbcompanion.model.ProfilePreflight.Tone;
import com.dbcompanion.repository.AiFeedbackRepository;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.repository.DatabaseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ProfilePreflightServiceTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final SessionDataSource source=new SessionDataSource();
    private final JdbcTemplate jdbc=new JdbcTemplate(source);
    private String feedbackSql="SELECT * FROM APP.VISIBLE";
    private int feedbackRows=1,detailReads;
    private boolean moreFeedback;
    private com.dbcompanion.model.ProfilePreflight.Check feedbackCheck(String objectList) {
        return service(new StubDatabase(objectList),new StubFeedback(false)).inspect("APP","P",true)
                .checks().stream().filter(c->c.key().equals("feedback")).findFirst().orElseThrow();
    }
    @Test void unqualifiedAndUnparseableFeedbackCannotBeGreen() {
        for(String sql:List.of("SELECT * FROM VISIBLE","not a select")) {
            feedbackSql=sql;
            assertThat(feedbackCheck("[{\"owner\":\"APP\"}]").tone()).isNotEqualTo(Tone.GREEN);
        }
    }
    @Test void outsideQualifiedObjectIsOnlyAWarningCandidate() {
        feedbackSql="SELECT * FROM OTHER.T";
        var check=feedbackCheck("[{\"owner\":\"APP\"}]");
        assertThat(check.tone()).isEqualTo(Tone.YELLOW);
        assertThat(check.summary()).contains("OTHER.T","candidate");
        assertThat(check.reason()).contains("does not prove");
    }
    @Test void schemaWidePermissionAndQuotedCaseAreNotLost() {
        feedbackSql="SELECT * FROM APP.VISIBLE";
        assertThat(feedbackCheck("[{\"owner\":\"APP\"}]").tone()).isEqualTo(Tone.GREEN);
        feedbackSql="SELECT * FROM \"APP\".\"MixedTable\"";
        assertThat(feedbackCheck("[{\"owner\":\"APP\",\"name\":\"MixedTable\"}]").tone()).isEqualTo(Tone.GREEN);
        assertThat(feedbackCheck("[{\"owner\":\"APP\",\"name\":\"MIXEDTABLE\"}]").tone()).isEqualTo(Tone.YELLOW);
    }
    @Test void furtherFeedbackPagesAreExplicitlyPartialEvenWhenFirstPageMatches() {
        moreFeedback=true;
        var check=feedbackCheck("[{\"owner\":\"APP\"}]");
        assertThat(check.tone()).isNotEqualTo(Tone.GREEN);
        assertThat(check.summary()).contains("PARTIAL");
    }
    @Test void referenceBoundStopsAtOneHundredAndReportsActualCount() {
        feedbackRows=10;
        var tables=java.util.stream.IntStream.range(0,11).mapToObj(i->"APP.T"+i).toList();
        feedbackSql="SELECT * FROM "+String.join(" CROSS JOIN ",tables);
        var check=feedbackCheck("[{\"owner\":\"APP\"}]");
        assertThat(check.tone()).isEqualTo(Tone.YELLOW);
        assertThat(check.summary()).contains("100/100 reference(s)","PARTIAL");
        assertThat(detailReads).isLessThanOrEqualTo(10);
    }
    @Test void rowBoundDoesNotCountAnUnreadEleventhRow() {
        feedbackRows=11;
        var check=feedbackCheck("[{\"owner\":\"APP\"}]");
        assertThat(check.summary()).contains("10/10 row(s)","PARTIAL").doesNotContain("11/10");
        assertThat(detailReads).isEqualTo(10);
    }

    @Test void metadata_and_bounded_feedback_distinguish_visible_from_not_visible() {
        var db=new StubDatabase("[{\"owner\":\"APP\",\"name\":\"VISIBLE\"},{\"owner\":\"APP\",\"name\":\"MISSING\"}]");
        var service=service(db,new StubFeedback(false));
        var result=service.inspect("APP","P",true);
        assertThat(result.fingerprint()).hasSize(64); assertThat(result.stale()).isFalse();
        assertThat(result.checks()).anySatisfy(c->{assertThat(c.key()).isEqualTo("objects");assertThat(c.tone()).isEqualTo(Tone.YELLOW);assertThat(c.summary()).contains("1 visible; 1 not visible");})
                .anySatisfy(c->{assertThat(c.key()).isEqualTo("feedback");assertThat(c.tone()).isEqualTo(Tone.GREEN);assertThat(c.summary()).contains("1/100 reference(s)");});
    }

    @Test void malformed_object_list_and_dictionary_or_feedback_errors_remain_explicit() {
        var malformed=service(new StubDatabase("not-json"),new StubFeedback(true)).inspect("APP","P",true);
        assertThat(malformed.checks()).anySatisfy(c->{assertThat(c.key()).isEqualTo("objects");assertThat(c.tone()).isEqualTo(Tone.RED);})
                .anySatisfy(c->{assertThat(c.key()).isEqualTo("feedback");assertThat(c.tone()).isEqualTo(Tone.GREY);});
        var denied=service(new StubDatabase("[{\"owner\":\"APP\",\"name\":\"DENIED\"}]"),new StubFeedback(false)).inspect("APP","P",true);
        assertThat(denied.checks()).anySatisfy(c->{assertThat(c.key()).isEqualTo("objects");assertThat(c.tone()).isEqualTo(Tone.GREY);});
    }

    private ProfilePreflightService service(StubDatabase db,StubFeedback feedback) {
        return new ProfilePreflightService(source,db,new StubCredentials(),feedback,json);
    }
    private final class StubDatabase extends DatabaseRepository {
        private final String objectList;
        StubDatabase(String objectList){super(jdbc);this.objectList=objectList;}
        @Override public List<AiProfile> profiles(String s,boolean own,String n){return List.of(new AiProfile("P","ENABLED","","2026-09-23","id",""));}
        @Override public List<AiProfileAttribute> profileAttributes(String s,boolean own,String n){return List.of(new AiProfileAttribute("object_list",objectList),new AiProfileAttribute("credential_name","C"));}
        @Override public ObjectMetadata objectMetadata(String schema,String name){if(name.equals("DENIED"))throw new IllegalStateException("ORA-01031");return name.equals("VISIBLE")?new ObjectMetadata(name,"TABLE",null):null;}
    }
    private final class StubCredentials extends CredentialCatalogRepository {
        StubCredentials(){super(jdbc);}
        @Override public Catalog list(String s,boolean own){return new Catalog(List.of(),"USER_CREDENTIALS","AVAILABLE","","now");}
    }
    private final class StubFeedback extends AiFeedbackRepository {
        private final boolean fail;
        StubFeedback(boolean fail){super(jdbc);this.fail=fail;}
        @Override public boolean tableExists(String s,String p){if(fail)throw new IllegalStateException("ORA-01031");return true;}
        @Override public AiFeedback.Page page(AiFeedback.Query q){return new AiFeedback.Page(java.util.stream.IntStream.range(0,feedbackRows).mapToObj(i->new AiFeedback.Item("AAABBBCCC000001abc","Q","negative","")).toList(),1,moreFeedback);}
        @Override public AiFeedback.Detail detail(AiFeedback.Query q,String id){detailReads++;return new AiFeedback.Detail("Q","negative","","",null,feedbackSql,"{}");}
    }
}
