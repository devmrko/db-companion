package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.GlossaryFunctionController;
import com.dbcompanion.model.*;
import com.dbcompanion.model.GlossaryFunction.*;
import com.dbcompanion.repository.GlossaryFunctionRepository;
import com.dbcompanion.service.GlossaryFunctionService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GlossaryFunctionTest {
    private String current="MISSING",dictionary="READY",text="READY";
    private boolean privilege=true;
    private int writes,reads;
    private final SessionDataSource source=new SessionDataSource();
    private final GlossaryFunctionRepository repo=new GlossaryFunctionRepository(new JdbcTemplate(source),null,new JsonMapper()){
        @Override public Status status(String owner){
            return new Status(owner,dictionary,text,current,privilege,List.of(),GlossaryFunctionSql.create(owner),GlossaryFunctionSql.example(owner));
        }
        @Override public Status install(String owner){
            if(!status(owner).installable())throw new IllegalArgumentException("Changed conditions");
            writes++;current="READY";return status(owner);
        }
        @Override public Object search(String owner,String question){
            if(!current.equals("READY"))throw new IllegalArgumentException("Not ready");
            reads++;return new JsonMapper().readTree("{\"aiCalled\":false,\"glossary\":{\"definitions\":[]}}");
        }
    };
    private final GlossaryFunctionService service=new GlossaryFunctionService(source,repo);
    private PoolSession session(){
        var s=new PoolSession(new HikariDataSource(),"LOW",()->{});
        s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
        s.metadata().selectSchema("OTHER");return s;
    }
    private MockHttpServletRequest request(PoolSession session){
        var r=new MockHttpServletRequest();r.setSession(new MockHttpSession());r.getSession().setAttribute(PoolSession.ATTRIBUTE,session);return r;
    }
    @Test void generatedModuleIsSearchOnlyBoundedInvokerRightsAndPortable(){
        var sql=GlossaryFunctionSql.create("APP");
        assertThat(sql).startsWith("CREATE FUNCTION \"APP\".\"DBC_GLOSSARY_SEARCH\"(p_question CLOB)")
            .contains("AUTHID CURRENT_USER","RETURN \"APP\".\"DBC_GLOSSARY_API\".SEARCH(p_question)");
        assertThat(sql.lines().count()).isLessThan(10);
        var scripts=GlossaryFunctionSql.statements("APP");
        assertThat(scripts).hasSize(4);
        assertThat(scripts.getFirst()).contains("AUTHID CURRENT_USER","FUNCTION SEARCH(p_question CLOB) RETURN CLOB");
        var body=scripts.get(1);
        assertThat(body).contains("CTX_DOC.POLICY_TOKENS","CTX_DOC.POLICY_HIGHLIGHT","CONTAINS(SEARCH_TEXT",
            "JSON_TABLE(occurrences","p_question)>8000","ids.get_size>30",
            "GETLENGTH(glossary.to_clob())>40000","response.put('aiCalled',FALSE)");
        assertThat(body).doesNotContain("TO_CLOB(","EXECUTE IMMEDIATE block_text","{{","DBMS_CLOUD_AI","CREATE OR REPLACE","AUTONOMOUS_TRANSACTION");
        int last=body.indexOf("FUNCTION SEARCH");
        for(String call:List.of("validate_question(p_question)","require_dictionary;","tokenize_question(question_text)",
            "build_text_query(tokens)","find_term_candidates(query_text)","match_question_terms(question_text,candidates)",
            "remove_overlapping_matches(matches)","load_term_definitions(matches)","build_search_response(question_text")){
            int next=body.indexOf(call,last+1);assertThat(next).isGreaterThan(last);last=next;
        }
        assertThat(GlossaryFunctionSql.example("APP")).contains(":question").doesNotContain("showsql");
        assertThat(GlossaryFunctionRepository.normalize("FUNCTION \"DBC_GLOSSARY_SEARCH\"\r\n RETURN CLOB")).isEqualTo("FUNCTION DBC_GLOSSARY_SEARCH\n RETURN CLOB");
        assertThat(GlossaryFunctionRepository.storedSource("APP",GlossaryFunctionSql.API,scripts.getFirst())).startsWith("PACKAGE DBC_GLOSSARY_API");
    }
    @Test void creationRequiresConsentUsesLoginOwnerAndConsumesPreviewOnce(){
        try(var s=session()){
            var controller=new GlossaryFunctionController(service);var r=request(s);
            var p=(Preview)controller.preview(r).getBody();assertThat(p.owner()).isEqualTo("APP");
            assertThat(controller.create(new Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);
            assertThat(writes).isZero();
            assertThat(controller.create(new Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.create(new Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);
            assertThat(writes).isEqualTo(1);
        }
    }
    @Test void expiredForeignChangedOrPermissionlessPreviewsNeverWrite(){
        try(var s=session()){
            var p=service.preview(s);
            for(var bad:List.of(new Preview(p.token(),"OTHER",p.sql(),p.expires()),new Preview(p.token(),p.owner(),p.sql(),Instant.EPOCH),
                new Preview(p.token(),p.owner(),"DROP TABLE T",p.expires())))
                assertThatThrownBy(()->service.install(s,bad)).isInstanceOf(IllegalArgumentException.class);
            for(String state:List.of("READY","CONFLICT","INVALID")){
                current=state;assertThatThrownBy(()->service.install(s,p)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(()->service.preview(s)).isInstanceOf(IllegalArgumentException.class);
            }
            current="MISSING";privilege=false;assertThatThrownBy(()->service.install(s,p)).isInstanceOf(IllegalArgumentException.class);
            privilege=true;dictionary="MISSING";assertThatThrownBy(()->service.preview(s)).isInstanceOf(IllegalArgumentException.class);
            dictionary="READY";text="CHECK_REQUIRED";assertThatThrownBy(()->service.preview(s)).isInstanceOf(IllegalArgumentException.class);
            assertThat(writes).isZero();
        }
    }
    @Test void testNeedsValidQuestionAndReadyModuleButNoAiProfile(){
        for(String invalid:List.of(""," ","x".repeat(8001)))assertThatThrownBy(()->new Question(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Question(null)).isInstanceOf(IllegalArgumentException.class);
        try(var s=session()){
            assertThatThrownBy(()->service.search(s,new Question("Revenue"))).isInstanceOf(IllegalArgumentException.class);
            current="READY";assertThat(service.search(s,new Question("Revenue")).toString()).contains("\"aiCalled\":false");assertThat(reads).isEqualTo(1);
        }
    }
    @Test void exactLegacyAndPartialModulesHaveAnExplicitMigrationPathButConflictsDoNot(){
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","READY","READY","MISSING"))).isEqualTo("INCOMPLETE");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","READY","READY","CONFLICT"))).isEqualTo("CONFLICT");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("MISSING","MISSING","MISSING"))).isEqualTo("MISSING");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("MISSING","MISSING","LEGACY"))).isEqualTo("LEGACY");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","MISSING","MISSING"))).isEqualTo("INCOMPLETE");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","READY","MISSING"))).isEqualTo("INCOMPLETE");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","READY","READY"))).isEqualTo("READY");
        for(var states:List.of(List.of("MISSING","READY","MISSING"),List.of("CONFLICT","READY","LEGACY"),List.of("READY","READY","CONFLICT")))
            assertThat(GlossaryFunctionRepository.moduleState(states)).isEqualTo("CONFLICT");
        assertThat(GlossaryFunctionRepository.moduleState(List.of("READY","INVALID","LEGACY"))).isEqualTo("INVALID");
        try(var s=session()){
            current="LEGACY";assertThat(service.preview(s).sql()).contains("DBC_GLOSSARY_API");
            current="INCOMPLETE";assertThat(service.preview(s).owner()).isEqualTo("APP");
            var p=service.preview(s);current="LEGACY";
            assertThatThrownBy(()->service.install(s,p)).isInstanceOf(IllegalArgumentException.class);
            assertThat(writes).isZero();
        }
    }
}
