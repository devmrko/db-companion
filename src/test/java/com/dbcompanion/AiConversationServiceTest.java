package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class AiConversationServiceTest {
    private int calls,timeout=15000;private String version="version-1",answer="{\"type\":\"final\",\"answer\":\"NEW_PROFILE를 추천합니다\",\"action\":\"\",\"arguments\":{}}";
    private final List<String> prompts=new ArrayList<>();private final JsonMapper json=new JsonMapper();
    private final Deque<String> replies=new ArrayDeque<>();
    private final SessionDataSource source=new SessionDataSource(){
        @Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true;case "getNetworkTimeout"->timeout;case "setNetworkTimeout"->{timeout=(int)a[1];yield null;}
            case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);
            default->m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });}
    };
    private final JdbcTemplate jdbc=new JdbcTemplate(source);
    private final AiAssistantRepository ai=new AiAssistantRepository(jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selected){return new AiAssistant.Profile(selected,"oci","MODEL",version);}
        @Override public List<AiAssistant.Choice> profiles(){return List.of(new AiAssistant.Choice("ACTUAL_PROFILE","oci","MODEL"));}
        @Override public String generate(String owner,String profile,String prompt,SelectAiTest.Action action){calls++;prompts.add(prompt);return replies.isEmpty()?answer:replies.removeFirst();}
    };
    private final ProfileHistoryRepository packages=new ProfileHistoryRepository(jdbc,json,null){@Override public String packageOwner(String owner){return owner;}};
    private final AiCreationService creation=new AiCreationService(source,null,null,json){
        @Override public ProfileOptions profileOptions(PoolSession s,String schema,String owner,String filter,String provider){return new ProfileOptions(List.of("APP"),new ProfileEdit.ObjectChoices(List.of(new ProfileEdit.ObjectChoice("ACTIVITY","TABLE")),false),List.of("MODEL"),List.of("REGION"));}
    };
    private final AiConversationService service=new AiConversationService(source,ai,packages,creation,null,null,null,json);
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));s.metadata().assistant().select(new AiAssistant.Selection("APP","HELPER"));return s;}
    private AiConversationService.Input input(String question){return new AiConversationService.Input(question,"HELPER",version,true,json.readTree("{\"creation\":{\"stage\":\"name\",\"draft\":{\"name\":\"\"}}}"));}
    private void workflow(String tool,String args){replies.clear();replies.add("{\"type\":\"tool_call\",\"name\":\""+tool+"\",\"arguments\":"+args+"}");replies.add("{\"type\":\"final\",\"answer\":\"prepared\"}");}
    @Test void phaseAndServerHeldHistoryReachTheNextActualModelCall(){try(var s=session()){
        service.message(s,input("뭘로 넣는 게 좋아?"),Locale.KOREAN);service.message(s,input("그걸로 해"),Locale.KOREAN);
        assertEquals(4,calls);assertTrue(prompts.getFirst().contains("\"stage\":\"name\""));assertTrue(prompts.get(2).contains("NEW_PROFILE"));assertTrue(prompts.get(2).contains("그걸로 해"));
        assertEquals(15000,timeout);
    }}
    @Test void otherSessionAndChangedHelperDoNotReceivePriorConversation(){try(var a=session();var b=session()){
        service.message(a,input("UNIQUE_PRIOR_TURN"),Locale.KOREAN);service.message(b,input("new session"),Locale.KOREAN);
        assertFalse(prompts.get(2).contains("UNIQUE_PRIOR_TURN"));version="version-2";service.message(a,input("new version"),Locale.KOREAN);assertFalse(prompts.get(4).contains("UNIQUE_PRIOR_TURN"));
    }}
    @Test void consentSchemaHelperAndVersionAreCheckedBeforeAnyModelCall(){try(var s=session()){
        assertThrows(IllegalArgumentException.class,()->service.message(s,new AiConversationService.Input("q","HELPER",version,false,json.readTree("{}")),Locale.KOREAN));
        assertThrows(AiAssistant.Failure.class,()->service.message(s,new AiConversationService.Input("q","HELPER","old",true,json.readTree("{}")),Locale.KOREAN));
        s.metadata().selectSchema("OTHER");assertThrows(AiAssistant.Failure.class,()->service.message(s,input("q"),Locale.KOREAN));assertEquals(0,calls);
    }}
    @Test void unregisteredOutputIsRejectedAndDoesNotPoisonFutureTurns(){try(var s=session()){
        workflow("workspace.navigate","{\"path\":\"https://unregistered.invalid\"}");
        assertThrows(IllegalArgumentException.class,()->service.message(s,input("FAILED_TURN"),Locale.KOREAN));
        replies.clear();answer="{\"type\":\"final\",\"answer\":\"ok\",\"action\":\"\",\"arguments\":{}}";service.message(s,input("next"),Locale.KOREAN);assertFalse(prompts.get(1).contains("FAILED_TURN"));
    }}
    @Test void latestStructuredSuggestionOverridesEarlierNameAndNeverEditsDatabase(){try(var s=session()){
        workflow("creation.suggest","{\"name\":\"FIRST_NAME\"}");service.message(s,input("recommend"),Locale.KOREAN);
        workflow("creation.suggest","{\"name\":\"LATEST_NAME\"}");service.message(s,input("another"),Locale.KOREAN);
        workflow("creation.updateDraft","{\"name\":\"LATEST_NAME\"}");service.message(s,input("use it"),Locale.KOREAN);
        assertTrue(prompts.get(2).contains("LAST_DRAFT_SUGGESTION {\"name\":\"LATEST_NAME\"}"));
    }}
    @Test void tableNamesAreBoundToOwnSchemaAndCheckedAgainstActualMetadata(){try(var s=session()){
        workflow("creation.updateDraft","{\"tables\":[\"ACTIVITY\"]}");
        var result=service.message(s,input("use ACTIVITY"),Locale.KOREAN);assertEquals("APP",result.arguments().path("tables").get(0).path("owner").asString());
        workflow("creation.updateDraft","{\"tables\":[\"OTHER.ACTIVITY\"]}");
        assertThrows(IllegalArgumentException.class,()->service.message(s,input("bad scope"),Locale.KOREAN));
        workflow("creation.updateDraft","{\"tables\":[\"MISSING\"]}");
        assertThrows(IllegalArgumentException.class,()->service.message(s,input("bad table"),Locale.KOREAN));
    }}
    @Test void aFinalAnswerAloneCannotAuthorizeDraftEdits(){try(var s=session()){
        answer="{\"type\":\"final\",\"answer\":\"listed\",\"action\":\"draft\",\"arguments\":{\"name\":\"UNREQUESTED\"}}";
        assertEquals("",service.message(s,input("what tables exist?"),Locale.KOREAN).action());
    }}
    @Test void existingBusinessDefinitionOpensAFilteredEditListWithoutCreatingAnything(){try(var s=session()){
        workflow("workspace.glossaryList","{\"filter\":\"ACTIVE_USER\",\"edit\":true}");
        var result=service.message(s,input("업무정의를 좀 바꿀까 하는데?"),Locale.KOREAN);
        assertEquals("glossary-list",result.action());assertEquals("ACTIVE_USER",result.arguments().path("filter").asString());assertTrue(result.arguments().path("edit").asBoolean());assertEquals(1,calls);
    }}
    @Test void invalidUnexecutedToolInputCanBeCorrectedUsingTheActualSchema(){try(var s=session()){
        replies.add("{\"type\":\"tool_call\",\"name\":\"workspace.glossaryList\",\"arguments\":{\"definition\":\"not yet a draft\"}}");
        replies.add("{\"type\":\"tool_call\",\"name\":\"workspace.glossaryList\",\"arguments\":{\"filter\":\"ACTIVE_USER\",\"edit\":true}}");
        var result=service.message(s,input("기존 정의를 수정하고 싶어"),Locale.KOREAN);
        assertEquals(2,result.modelCalls());assertEquals("glossary-list",result.action());assertTrue(prompts.get(1).contains("INVALID_TOOL_ARGUMENTS"));assertTrue(prompts.get(1).contains("inputSchema"));
        var rejected=json.readTree(result.executions().getFirst().result());assertFalse(rejected.path("executed").asBoolean());
    }}
    @Test void controllerReturnsAnActionableDiagnosticWithoutEchoingInvalidInput(){try(var s=session()){
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
        var input=new AiConversationService.Input("","HELPER",version,true,json.readTree("{\"password\":\"SYNTHETIC_SECRET\"}"));
        var response=new com.dbcompanion.controller.AiConversationController(service).message(input,Locale.KOREAN,request);
        assertEquals(400,response.getStatusCode().value());var body=json.valueToTree(response.getBody());
        assertEquals("CONVERSATION_INPUT",body.path("diagnostic").path("code").asString());
        String id=body.path("diagnostic").path("requestId").asString();assertEquals(id,response.getHeaders().getFirst("X-Request-ID"));assertDoesNotThrow(()->UUID.fromString(id));
        assertFalse(body.toString().contains("SYNTHETIC_SECRET"));assertEquals(0,calls);
    }}
    @Test void allSidebarMenusAreInTheModelReferenceCatalogue() throws java.io.IOException {
        try(var input=getClass().getResourceAsStream("/templates/fragments/common.html")){
            var matcher=java.util.regex.Pattern.compile("class=\"app-nav-item\"[^\\n]*?th:href=\"@\\{([^}]+)\\}").matcher(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            int count=0;while(matcher.find()){assertTrue(AiConversationService.menuPaths().contains(matcher.group(1)),matcher.group(1));count++;}assertTrue(count>25);
        }
    }
}
