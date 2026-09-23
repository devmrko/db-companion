package com.dbcompanion;

import com.dbcompanion.common.db.TeamEditPolicy;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.TeamEdit.*;
import com.dbcompanion.repository.TeamEditRepository;
import com.dbcompanion.repository.TeamHistoryRepository;
import com.dbcompanion.service.TeamEditService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Pure production-policy tests. No simulated Oracle package, authentication or database. */
class TeamEditPolicyTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private Target target(String attribute){return new Target("APP","TEAM",attribute);}
    private Component team(String id,String agents,String process){return new Component("TEAM",new Item(id,"TEAM","description","ENABLED","created","modified"),List.of(new Attribute("TEAM","agents",agents,"time"),new Attribute("TEAM","process",process,"time")));}
    private final String pairs="[{\"name\":\"AGENT\",\"task\":\"TASK\"}]";
    @Test void requiresOwnerSelectedSchemaAndEditableTeamAttribute() {
        TeamEditPolicy.scope("APP","APP",target("agents"));
        assertThatThrownBy(()->TeamEditPolicy.scope("ADMIN","APP",target("agents"))).isInstanceOf(MetadataEditException.class).hasMessageContaining("owner");
        assertThatThrownBy(()->TeamEditPolicy.scope("APP","OTHER",target("agents"))).hasMessageContaining("schema");
        assertThatThrownBy(()->TeamEditPolicy.scope("APP","APP",target("description"))).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->TeamEditPolicy.scope("APP","APP",target(null))).isInstanceOf(MetadataEditException.class);
    }
    @Test void validatesPairsAndKeepsUnknownJsonFieldsWithoutInventingTasks() {
        TeamEditPolicy.input(target("agents"),pairs,json);
        assertThat(TeamEditPolicy.assignments("[{\"name\":\"Agent\",\"task\":\"Task\",\"extra\":{\"keep\":true}}]",json)).containsExactly(new Assignment("AGENT","TASK"));
        assertThat(TeamEditPolicy.assignments("[{\"name\":\"AGENT\",\"task\":\"TASK\"},{\"name\":\"AGENT\",\"task\":\"SECOND\"}]",json)).hasSize(2);
        for(String bad:List.of("[]","{}","null","invalid","[{}]","[{\"name\":1,\"task\":\"T\"}]","[{\"name\":\"A\",\"task\":\"T\"},{\"name\":\"a\",\"task\":\"t\"}]"))
            assertThatThrownBy(()->TeamEditPolicy.input(target("agents"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void preservesQuotedNamesAndValidatesBytesAndUnicode() {
        assertThat(TeamEditPolicy.referenceName("\"MiX\"\"ed\"")).isEqualTo("MiX\"ed");
        for(String bad:List.of(""," ","\0","\uD800","한".repeat(11000)))
            assertThatThrownBy(()->TeamEditPolicy.input(target("supervisor_agent"),bad,json)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->TeamEditPolicy.identifier("한".repeat(43))).isInstanceOf(MetadataEditException.class);
    }
    @Test void processAndMemoryAreTyped() {
        TeamEditPolicy.input(target("process"),"sequential",json);
        TeamEditPolicy.input(target("long_term_memory_length"),"30",json);
        assertThatThrownBy(()->TeamEditPolicy.input(target("process"),"parallel",json)).isInstanceOf(MetadataEditException.class);
        for(String bad:List.of("0","-1","1.2","1e2","NaN"))
            assertThatThrownBy(()->TeamEditPolicy.input(target("long_term_memory_length"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void fingerprintCoversRecreationOtherAttributesAndOriginalDates() {
        var original=team("1",pairs,"sequential");String version=TeamEditPolicy.version(target("agents"),original,json);
        TeamEditPolicy.verifyVersion(target("agents"),original,version,json);
        for(Component changed:List.of(team("2",pairs,"sequential"),team("1",pairs,"other"),team("1","[]","sequential")))
            assertThatThrownBy(()->TeamEditPolicy.verifyVersion(target("agents"),changed,version,json)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->TeamEditPolicy.verifyVersion(target("agents"),original,null,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void readbackMustMatchOnlyRequestedChangeAndSameTeamIdentity() {
        var before=team("1",pairs,"sequential");String changed="[{\"name\":\"B\",\"task\":\"T\"}]";
        assertThat(TeamEditPolicy.readbackMatches(target("agents"),before,team("1",changed,"sequential"),changed,json)).isTrue();
        assertThat(TeamEditPolicy.readbackMatches(target("agents"),before,team("2",changed,"sequential"),changed,json)).isFalse();
        assertThat(TeamEditPolicy.readbackMatches(target("agents"),before,team("1",changed,"parallel"),changed,json)).isFalse();
        assertThat(TeamEditPolicy.readbackMatches(target("agents"),before,new Component("TEAM",null,List.of()),changed,json)).isFalse();
        assertThat(TeamEditPolicy.equivalent("agents",pairs,"[ { \"task\":\"TASK\",\"name\":\"AGENT\"} ]",json)).isTrue();
    }
    @Test void apiCapabilityRequiresOneExactVarcharOverload() {
        var args=List.of(new TeamEditRepository.Argument(1,1,"OBJECT_NAME","VARCHAR2","IN"),new TeamEditRepository.Argument(1,2,"OBJECT_TYPE","VARCHAR2","IN"),new TeamEditRepository.Argument(1,3,"ATTRIBUTE_NAME","VARCHAR2","IN"),new TeamEditRepository.Argument(1,4,"ATTRIBUTE_VALUE","VARCHAR2","IN"));
        assertThat(TeamEditRepository.compatible(args)).isTrue();
        assertThat(TeamEditRepository.compatible(args.subList(0,3))).isFalse();
        var mixed=new java.util.ArrayList<>(args);mixed.set(3,new TeamEditRepository.Argument(2,4,"ATTRIBUTE_VALUE","VARCHAR2","IN"));
        assertThat(TeamEditRepository.compatible(mixed)).isFalse();
    }
    @Test void sqlOnlyUpdatesBoundTeamAttributeAndNeverRecreatesObjects() {
        String sql=TeamEditRepository.sql("C##CLOUD$SERVICE");
        assertThat(sql).contains("\"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI_AGENT\".SET_ATTRIBUTE", "object_type => 'TEAM'");
        assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(3);
        assertThat(sql).doesNotContain("DROP_","CREATE_","RUN_","SET_TEAM(");
        assertThat(TeamHistoryRepository.createSql("APP")).contains("GENERATED ALWAYS AS IDENTITY","BEFORE_JSON CLOB","AFTER_JSON CLOB");
    }
    @Test void successRequiresReadbackAndAfterHistoryAndNoApiError() {
        for(boolean match:new boolean[]{false,true})for(boolean archive:new boolean[]{false,true})
            assertThat(TeamEditService.result(match,archive,null,null,null).verified()).isEqualTo(match&&archive);
        var error=new RuntimeException(new SQLException("ORA-20053: Invalid value\nprivate SQL value","99999",20053));
        var result=TeamEditService.result(true,true,error,null,null);
        assertThat(result.verified()).isFalse();assertThat(result.message()).contains("ORA-20053", "자동 롤백하거나 재시도하지 않았습니다").doesNotContain("private SQL value");
    }
    @Test void serviceCommitsOriginalBeforeCallAndRepositoryBindsValues() throws Exception {
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/TeamEditService.java"));
        int before=service.indexOf("archive.before("),committed=service.indexOf("if(before.unchanged())"),recheck=service.indexOf("TeamEditPolicy.verifyVersion",committed),call=service.indexOf("attempted.set(true);repository.save("),readback=service.indexOf("after=read.execute"),after=service.indexOf("archive.after(");
        assertThat(before).isPositive();assertThat(committed).isGreaterThan(before);assertThat(recheck).isGreaterThan(committed);assertThat(call).isGreaterThan(recheck);assertThat(readback).isGreaterThan(call);assertThat(after).isGreaterThan(readback);
        assertThat(service).contains("if(!attempted.get())throw ex", "source.clear()").doesNotContain("CREATE AUDIT", "GRANT ","RUN_TEAM");
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/TeamEditRepository.java"));
        assertThat(repository).contains("statement.setString(1,target.team())","statement.setString(2,target.attribute())","statement.setString(3,value)","ORACLE_MAINTAINED='Y'", "DATA_LEVEL=0");
    }
}
