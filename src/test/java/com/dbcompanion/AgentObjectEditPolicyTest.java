package com.dbcompanion;

import com.dbcompanion.common.db.AgentObjectEditPolicy;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.AgentObjectEdit.*;
import com.dbcompanion.repository.AgentObjectEditRepository;
import com.dbcompanion.repository.AgentObjectHistoryRepository;
import com.dbcompanion.service.AgentObjectEditService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.dbcompanion.common.db.AgentObjectEditPolicy.*;

/** Pure policy and SQL contract checks, no database replacement or mocked package. */
class AgentObjectEditPolicyTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private Target target(Kind kind,String attribute){return new Target("APP",kind,"OBJECT",attribute);}
    private Component object(String id,String instruction,String tools) {return new Component("OBJECT",new Item(id,"OBJECT","description","ENABLED","created","modified"),List.of(new Attribute("OBJECT","instruction",instruction,"time"),new Attribute("OBJECT","tools",tools,"time")));}
    @Test void ownerAndSelectedSchemaAndThreeKindsAreRequired() {
        for(Kind kind:List.of(Kind.AGENT,Kind.TASK,Kind.TOOL))scope("APP","APP",target(kind,"role").history());
        assertThatThrownBy(()->scope("ADMIN","APP",target(Kind.AGENT,"role").history())).hasMessageContaining("owner");
        assertThatThrownBy(()->scope("APP","OTHER",target(Kind.TASK,"tools").history())).hasMessageContaining("schema");
        assertThatThrownBy(()->scope("APP","APP",target(Kind.TEAM,"agents").history())).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->scope("APP","APP",new HistoryTarget("APP",null,"X"))).isInstanceOf(MetadataEditException.class);
    }
    @Test void immutableAndCrossKindAttributesAreRejected() {
        for(var target:List.of(target(Kind.AGENT,"supervisor"),target(Kind.TASK,"profile_name"),target(Kind.TOOL,"status"),target(Kind.TASK,"role"),target(Kind.AGENT,"description")))
            assertThatThrownBy(()->input(target,"x",json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void textKeepsLongUnicodeAndNeverTruncates() {
        String text="원문\n{query}\n"+"긴 지침 ".repeat(1700);
        input(target(Kind.TASK,"instruction"),text,json);
        for(String bad:List.of(""," ","\0","\uD800","한".repeat(11000)))assertThatThrownBy(()->input(target(Kind.AGENT,"role"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void booleansIntegersReferencesAndFunctionIdentifiersAreTyped() {
        for(String value:List.of("true","false","True"))input(target(Kind.AGENT,"enable_human_tool"),value,json);
        input(target(Kind.AGENT,"short_term_memory_length"),"30",json);
        for(String bad:List.of("0","-1","1.5","1e2"))assertThatThrownBy(()->input(target(Kind.AGENT,"short_term_memory_length"),bad,json)).isInstanceOf(MetadataEditException.class);
        assertThat(reference("\"MiX\"\"ed\"")).isEqualTo("MiX\"ed");
        assertThatThrownBy(()->reference("OTHER.PROFILE")).isInstanceOf(MetadataEditException.class);
        input(target(Kind.TOOL,"function"),"APP.PKG.PROC",json);
        for(String bad:List.of("FN()","FN@REMOTE","BEGIN FN; END;"))assertThatThrownBy(()->input(target(Kind.TOOL,"function"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void toolsAreUniqueReferencesAndMayBeEmpty() {
        input(target(Kind.TASK,"tools"),"[]",json);
        assertThat(names("[\"one\",\"\\\"MiX\\\"\"]",json)).containsExactly("ONE","MiX");
        for(String bad:List.of("null","{}","[1]","[\"A\",\"a\"]","[\"A.B\"]"))assertThatThrownBy(()->input(target(Kind.AGENT,"tools"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void taskDependenciesRejectSelfAndCyclesWithoutScanningAllTasks() {
        validateTaskChain("A","B",Map.of("B","C")::get);
        assertThatThrownBy(()->validateTaskChain("A","a",n->null)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->validateTaskChain("A","B",Map.of("B","C","C","B")::get)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->validateTaskChain("A","T0",n->"T"+(Integer.parseInt(n.substring(1))+1))).isInstanceOf(MetadataEditException.class);
    }
    @Test void toolParamsEnforceTypeSpecificRequiredFieldsAndKeepUnknownKeys() {
        var params=AgentObjectEditRepository.validateToolParams("SQL","{\"profile_name\":\"P\",\"extra\":{\"retain\":true}}",json);
        assertThat(params.get("extra").get("retain").booleanValue()).isTrue();
        for(String type:List.of("SQL","RAG","WEBSEARCH","NOTIFICATION"))assertThatThrownBy(()->AgentObjectEditRepository.validateToolParams(type,"{}",json)).isInstanceOf(MetadataEditException.class);
        AgentObjectEditRepository.validateToolParams("NOTIFICATION","{\"notification_type\":\"email\",\"credential_name\":\"C\",\"sender\":\"a\",\"recipient\":\"b\",\"smtp_host\":\"host\"}",json);
        assertThatThrownBy(()->AgentObjectEditRepository.validateToolParams("SQL","{\"profile_name\":7}",json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void toolInputsPreserveExtraFieldsAndRequireUniqueNamedObjects() {
        input(target(Kind.TOOL,"tool_inputs"),"[{\"name\":\"arg\",\"description\":\"한글\",\"extra\":true}]",json);
        for(String bad:List.of("{}","[{}]","[{\"name\":\"x\",\"description\":false}]","[{\"name\":\"x\"},{\"name\":\"x\"}]"))assertThatThrownBy(()->input(target(Kind.TOOL,"tool_inputs"),bad,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void fingerprintsIsolateKindAndCoverFullObjectAndAttributeTimestamps() {
        var current=object("1","old","[]");var t=target(Kind.TASK,"instruction");String version=version(t,current,json);
        verifyVersion(t,current,version,json);
        assertThat(version(target(Kind.TOOL,"instruction"),current,json)).isNotEqualTo(version);
        for(var changed:List.of(object("2","old","[]"),object("1","new","[]"),object("1","old","[\"T\"]")))assertThatThrownBy(()->verifyVersion(t,changed,version,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void readbackRequiresSameIdentityAndOtherValues() {
        var before=object("1","old","[]");var t=target(Kind.TASK,"instruction");
        assertThat(readbackMatches(t,before,object("1","new","[]"),"new",json)).isTrue();
        assertThat(readbackMatches(t,before,object("2","new","[]"),"new",json)).isFalse();
        assertThat(readbackMatches(t,before,object("1","new","[\"TOOL\"]"),"new",json)).isFalse();
        assertThat(readbackMatches(t,before,new Component("OBJECT",null,List.of()),"new",json)).isFalse();
        assertThat(equivalent("instruction","one\n","one",json)).isFalse();
        assertThat(equivalent("tool_params","{\"x\":1}","{ \"x\": 1 }",json)).isTrue();
    }
    @Test void sqlUsesFixedKindBoundValuesAndExactQuotedObjectName() {
        for(Kind kind:List.of(Kind.AGENT,Kind.TASK,Kind.TOOL)) {
            String sql=AgentObjectEditRepository.sql("C##CLOUD$SERVICE",kind);
            assertThat(sql).contains("object_type => '"+kind.name()+"'",".SET_ATTRIBUTE(").doesNotContain("DROP_","CREATE_","RUN_");
            assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(3);
        }
        assertThat(AgentObjectEditRepository.apiName("MiX\"Name")).isEqualTo("\"MiX\"\"Name\"");
        assertThatThrownBy(()->AgentObjectEditRepository.sql("SYS",Kind.TEAM)).isInstanceOf(MetadataEditException.class);
        assertThat(AgentObjectHistoryRepository.createSql("APP")).contains("DBC_AI_HISTORY","OBJECT_TYPE","BEFORE_JSON CLOB","AFTER_JSON CLOB").doesNotContain("DBC_TEAM_EDIT_HISTORY");
    }
    @Test void preSaveArchiveCommitThenVersionCheckCallReadbackAndPostArchiveAreRequired() throws Exception {
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/AgentObjectEditService.java"));
        int before=service.indexOf("archive.before("),committed=service.indexOf("if(before.unchanged())"),recheck=service.indexOf("AgentObjectEditPolicy.verifyVersion",committed),call=service.indexOf("attempted.set(true);repository.save("),readback=service.indexOf("after=read.execute"),after=service.indexOf("archive.after(");
        assertThat(before).isPositive();assertThat(committed).isGreaterThan(before);assertThat(recheck).isGreaterThan(committed);assertThat(call).isGreaterThan(recheck);assertThat(readback).isGreaterThan(call);assertThat(after).isGreaterThan(readback);
        String archive=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AgentObjectHistoryRepository.java"));
        assertThat(archive).contains("OBJECT_TYPE=? AND OBJECT_NAME=?", "OBJECT_TYPE=? AND OBJECT_NAME=? AND SEQ=?", "common.before", "common.after", "common.install").doesNotContain("DROP ","TRUNCATE ");
        for(boolean matches:new boolean[]{true,false})for(boolean archived:new boolean[]{true,false})assertThat(AgentObjectEditService.result(matches,archived,null,null,null).verified()).isEqualTo(matches&&archived);
        assertThat(AgentObjectEditService.result(true,true,new IllegalStateException("private-value"),null,null).verified()).isFalse();
    }
}
