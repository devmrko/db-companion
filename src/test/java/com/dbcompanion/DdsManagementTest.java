package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.DdsManagement.*;
import com.dbcompanion.repository.DdsManagementRepository;
import com.dbcompanion.service.DdsManagementService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class DdsManagementTest {
    static Map<String,String> row(String... values){var m=new TreeMap<String,String>();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    static State state(List<Map<String,String>> grants){return new State(List.of(row("DATA_ROLE","R","MAPPED_TO",null,"ENABLED_BY_DEFAULT","TRUE")),grants,List.of(row("TABLE_NAME","T","COLUMN_NAME","C","DATA_TYPE","NUMBER"),row("TABLE_NAME","T","COLUMN_NAME","TXT","DATA_TYPE","VARCHAR2")),Set.of("CREATE DATA GRANT","CREATE DATA ROLE","ADMINISTER ANY DATA GRANT"));}
    static Draft draft(String action,String value){return new Draft("APP","GRANT",action,"G","T","R","C","NUMBER",value,false);}
    static Map<String,String> grant(String predicate){return row("OWNER","APP","GRANT_NAME","G","PRIVILEGE","SELECT","COLUMN_NAME",null,"GRANTED_WITH_ALL_COLUMNS_EXCEPT",null,"OBJECT_OWNER","APP","OBJECT_NAME","T","OBJECT_TYPE","TABLE","PREDICATE",predicate,"GRANTEE","R","GRANTEE_TYPE","DATA ROLE","USE_DATA_GRANTS_ONLY","FALSE","CROSS_TABLE_DATA_GRANT","FALSE","START_TIME",null,"END_TIME",null,"INVALID_COLUMN_NAME",null);}
    @Test void generatedPolicyUsesOnlyOwnedTableLocalRoleAndValidatedNumericValue(){
        assertThat(DdsManagement.sql(draft("CREATE","10.00"),state(List.of()),"APP")).isEqualTo("CREATE DATA GRANT \"APP\".\"G\" AS SELECT ON \"APP\".\"T\" WHERE \"C\" = 10 TO \"R\"");
        for(String value:List.of("1; DROP TABLE T","0 OR 1=1","1--","NaN","1e99",""))assertThatThrownBy(()->DdsManagement.sql(draft("CREATE",value),state(List.of()),"APP")).hasMessage("invalid");
        assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),state(List.of()),"OTHER")).hasMessage("ownSchema");
        for(String name:List.of("SYS.X","A\" B","a","X;DROP","한글"))assertThatThrownBy(()->DdsManagement.id(name)).hasMessage("invalid");
    }
    @Test void unicodeApostrophesAreQuotedWithoutChangingPolicyStructure(){
        var d=new Draft("APP","GRANT","CREATE","G","T","R","TXT","TEXT","한국어 ' 🧪",false);
        assertThat(DdsManagement.sql(d,state(List.of()),"APP")).contains("\"TXT\" = '한국어 '' 🧪'");
        var r=grant("\"TXT\" = '한국어 '' 🧪'");DdsManagement.verify(d,state(List.of(r)));
    }
    @Test void replacementAndRestorePreserveSupportedOriginalValues(){
        var before=state(List.of(grant("\"C\" = 10")));var update=draft("UPDATE","20");
        var previous=DdsManagement.previous(update,before);assertThat(previous.value()).isEqualTo("10");
        assertThat(DdsManagement.sql(update,before,"APP")).startsWith("CREATE OR REPLACE DATA GRANT");
        DdsManagement.verify(update,state(List.of(grant("C=20.00"))));
        assertThatThrownBy(()->DdsManagement.verify(update,before)).hasMessage("verifyFailed");
        assertThat(DdsManagement.sql(previous,state(List.of(grant("C=20"))),"APP")).contains("\"C\" = 10");
    }
    @Test void richOrUnknownPoliciesAreNeverSilentlyReplaced(){
        for(var change:List.of(row("FUTURE_POLICY","x"),row("CROSS_TABLE_DATA_GRANT","TRUE"),row("COLUMN_NAME","C"),row("USE_DATA_GRANTS_ONLY","TRUE"),row("START_TIME","2026"),row("PRIVILEGE","UPDATE"),row("GRANTEE_TYPE","END USER"),row("OBJECT_OWNER","OTHER"),row("PREDICATE","C=1 OR C=2"))){
            var original=grant("C=10");original.putAll(change);
            assertThatThrownBy(()->DdsManagement.sql(draft("UPDATE","20"),state(List.of(original)),"APP")).hasMessage("unsupported");
        }
        assertThatThrownBy(()->DdsManagement.sql(draft("UPDATE","20"),state(List.of(grant("C=10"),grant("C=10"))),"APP")).hasMessage("unsupported");
    }
    @Test void absentDuplicatePrivilegesAndTypesFailClosed(){
        assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),state(List.of(grant("C=10"))),"APP")).hasMessage("exists");
        assertThatThrownBy(()->DdsManagement.sql(draft("UPDATE","1"),state(List.of()),"APP")).hasMessage("missing");
        State s=state(List.of());assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),new State(s.roles(),s.grants(),s.columns(),Set.of()),"APP")).hasMessage("grantPrivilege");
        assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),new State(s.roles(),s.grants(),s.columns(),Set.of("CREATE DATA GRANT")),"APP")).hasMessage("policyPrivilege");
        assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),new State(s.roles(),s.grants(),List.of(),s.privileges()),"APP")).hasMessage("columnType");
        assertThatThrownBy(()->DdsManagement.sql(draft("CREATE","1"),new State(List.of(),s.grants(),s.columns(),s.privileges()),"APP")).hasMessage("localRole");
    }
    @Test void currentCatalogMetadataAcceptsOnlyOrdinaryUntaggedDataGrants(){
        var actual=grant("\"C\" = 10");actual.put("GRANT_TYPE","DATA GRANT");actual.put("TAG_NAME",null);
        DdsManagement.verify(draft("CREATE","10"),state(List.of(actual)));
        assertThat(DdsManagement.sql(draft("UPDATE","20"),state(List.of(actual)),"APP")).contains("OR REPLACE DATA GRANT");
        actual.put("TAG_NAME","CLASSIFIED");
        assertThatThrownBy(()->DdsManagement.previous(draft("UPDATE","20"),state(List.of(actual)))).hasMessage("unsupported");
        actual.put("TAG_NAME",null);actual.put("GRANT_TYPE","OTHER");
        assertThatThrownBy(()->DdsManagement.previous(draft("UPDATE","20"),state(List.of(actual)))).hasMessage("unsupported");
    }
    @Test void localRoleManagementRequiresAdminAndRejectsMappedRoles(){
        var create=new Draft("ADMIN","ROLE","CREATE","NEW_ROLE","","","","","",false);
        assertThat(DdsManagement.sql(create,state(List.of()),"ADMIN")).isEqualTo("CREATE DATA ROLE \"NEW_ROLE\" DISABLED");
        var ordinary=new Draft("APP","ROLE","CREATE","NEW_ROLE","","","","","",false);
        assertThatThrownBy(()->DdsManagement.sql(ordinary,state(List.of()),"APP")).hasMessage("adminRole");
        var mapped=new State(List.of(row("DATA_ROLE","R","MAPPED_TO","IAM_OAUTH_GROUP=x","ENABLED_BY_DEFAULT","TRUE")),List.of(),List.of(),Set.of("CREATE DATA ROLE"));
        assertThatThrownBy(()->DdsManagement.sql(new Draft("ADMIN","ROLE","UPDATE","R","","","","","",false),mapped,"ADMIN")).hasMessage("roleType");
    }
    @Test void ociGroupMappingSupportsReadbackReplacementAndGrantAssignment(){
        var base=state(List.of());var create=new Draft("ADMIN","ROLE","CREATE","OCI_ROLE","","","","","",false,"IAM_OAUTH_GROUP=Group '한글'");
        assertThat(DdsManagement.sql(create,base,"ADMIN")).contains("MAPPED TO 'IAM_OAUTH_GROUP=Group ''한글'''").doesNotContain(" DISABLED");
        var mapped=new State(List.of(row("DATA_ROLE","OCI_ROLE","MAPPED_TO",create.mapping(),"ENABLED_BY_DEFAULT",null)),List.of(),base.columns(),base.privileges());
        DdsManagement.verify(create,mapped);
        assertThat(DdsManagement.previous(create,mapped).mapping()).isEqualTo(create.mapping());
        var update=new Draft("ADMIN","ROLE","UPDATE","OCI_ROLE","","","","","",true,"IAM_GROUP_NAME=Domain/Group");
        assertThat(DdsManagement.sql(update,mapped,"ADMIN")).contains("OR REPLACE DATA ROLE","IAM_GROUP_NAME=Domain/Group");
        assertThat(DdsManagement.sql(new Draft("APP","GRANT","CREATE","G","T","OCI_ROLE","C","NUMBER","10",false),mapped,"APP")).endsWith("TO \"OCI_ROLE\"");
        var duplicate=new Draft("ADMIN","ROLE","CREATE","OTHER","","","","","",false,create.mapping());
        assertThatThrownBy(()->DdsManagement.sql(duplicate,mapped,"ADMIN")).hasMessage("mappingExists");
        for(String value:List.of("IAM_OAUTH_GROUP=", "IAM_OAUTH_GROUP=D/G", "IAM_GROUP_NAME=\nX", "OTHER=G"))assertThatThrownBy(()->DdsManagement.mapping(value)).hasMessage("mappingInvalid");
    }
    static final class Fixture implements AutoCloseable {
        State current=state(List.of());int writes;boolean fail;
        final SessionDataSource source=new SessionDataSource();
        final DdsManagementRepository repository=new DdsManagementRepository(new JdbcTemplate(source)){
            @Override public State state(String owner){return current;}
            @Override public void execute(String sql){writes++;if(fail)throw new IllegalStateException("synthetic uncertain DDL");current=DdsManagementTest.state(List.of(grant("C=10")));}
        };
        final DdsManagementService service=new DdsManagementService(source,repository);
        final PoolSession session=new PoolSession(new HikariDataSource(),"SYNTHETIC",()->{});
        Fixture(){session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));}
        Preview preview(){return service.preview(session,new PreviewRequest(draft("CREATE","10"),OrdsManagement.digest(current)));}
        Apply apply(Preview p){return new Apply(p.token(),p.confirmation(),true);}
        @Override public void close(){session.close();}
    }
    @Test void appliedDdlIsReadBackAndTokenCannotReplay(){try(var f=new Fixture()){
        var p=f.preview();assertThat(f.writes).isZero();assertThat(f.service.apply(f.session,f.apply(p))).containsEntry("status","VERIFIED");
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
    }}
    @Test void successfulDdlWithWrongReadbackIsNotReportedAsVerified(){try(var f=new Fixture()){
        var request=new PreviewRequest(draft("CREATE","20"),OrdsManagement.digest(f.current));
        var p=f.service.preview(f.session,request);
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p))).hasMessage("verifyFailed");
        assertThat(f.writes).isEqualTo(1);
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p))).hasMessage("expired");
    }}
    @Test void staleFormStalePreviewAndCrossSessionCannotExecute(){try(var f=new Fixture();var other=new Fixture()){
        assertThatThrownBy(()->f.service.preview(f.session,new PreviewRequest(draft("CREATE","10"),"old"))).hasMessage("stale");
        var p=f.preview();assertThatThrownBy(()->other.service.apply(other.session,f.apply(p))).hasMessage("expired");
        f.current=state(List.of(grant("C=20")));assertThatThrownBy(()->f.service.apply(f.session,f.apply(p))).hasMessage("stale");assertThat(f.writes).isZero();
    }}
    @Test void consentAndSchemaSwitchBlockDdlAndUncertainFailureNeverRetries(){try(var f=new Fixture()){
        var p=f.preview();assertThatThrownBy(()->f.service.apply(f.session,new Apply(p.token(),p.confirmation(),false))).hasMessage("confirm");assertThat(f.writes).isZero();
        var p2=f.preview();f.session.metadata().selectSchema("OTHER");assertThatThrownBy(()->f.service.apply(f.session,f.apply(p2))).hasMessage("ownSchema");assertThat(f.writes).isZero();
        f.session.metadata().selectSchema("APP");var p3=f.preview();f.fail=true;assertThatThrownBy(()->f.service.apply(f.session,f.apply(p3))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p3))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
    }}
}
