package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.AiAssistant.*;
import com.dbcompanion.repository.AiAssistantRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiAssistantTest {
    private final Instant now=Instant.parse("2026-09-19T01:00:00Z");
    private final Selection selected=new Selection("APP","LLM");
    private Profile profile(){return new Profile(selected,"oci","model","version");}
    private State state(){var state=new State();state.select(selected);return state;}
    private Preview prepare(State state){return state.prepare("APP",profile(),"APP.F","function f return number as begin return 1; end;",false,"ko",now);}
    private RoutineSource.Definition definition(String source){return new RoutineSource.Definition("APP","F",null,"FUNCTION",List.of(new RoutineSource.Section("FUNCTION",source)));}
    @Test void selectionIsLoginScopedAndSurvivesBrowsingSchemaChange(){
        var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));
        session.assistant().select(selected);session.selectSchema("OTHER");assertThat(session.assistant().selected()).isEqualTo(selected);
        assertThat(new DatabaseSession(session.info(),List.of("APP")).assistant().selected()).isNull();
        session.assistant().select(null);assertThat(session.assistant().selected()).isNull();
    }
    @Test void profilesCacheUntilExplicitRefreshAndFailedRefreshDiscardsCache(){
        var state=state();var count=new AtomicInteger();java.util.function.Supplier<List<Choice>> load=()->{count.incrementAndGet();return List.of(new Choice("LLM","oci",null));};
        state.profiles(false,load);assertThatThrownBy(()->state.profiles(false,load).clear()).isInstanceOf(UnsupportedOperationException.class);assertThat(count).hasValue(1);
        state.profiles(true,load);assertThat(count).hasValue(2);
        assertThatThrownBy(()->state.profiles(true,()->{throw new IllegalStateException("unavailable");})).hasMessage("unavailable");
        state.profiles(false,load);assertThat(count).hasValue(3);
    }
    @Test void consentRequiredAndTokenCanOnlyBeConsumedOnceEvenAfterFailure(){
        var state=state();var preview=prepare(state);
        assertThatThrownBy(()->state.consume(preview.token(),false,"APP",now)).isInstanceOf(Failure.class);
        assertThat(state.consume(preview.token(),true,"APP",now).preview()).isEqualTo(preview);
        state.finish();assertThatThrownBy(()->state.consume(preview.token(),true,"APP",now)).isInstanceOf(Failure.class);
    }
    @Test void expiredOrWrongSchemaRequestsCannotBeResurrected(){
        for(boolean expired:List.of(false,true)){
            var state=state();var preview=prepare(state);
            assertThatThrownBy(()->state.consume(preview.token(),true,expired?"APP":"OTHER",expired?now.plusSeconds(600):now)).isInstanceOf(Failure.class);
            assertThatThrownBy(()->state.consume(preview.token(),true,"APP",now)).isInstanceOf(Failure.class);
        }
    }
    @Test void newPreviewAndSelectionChangesInvalidatePriorConsent(){
        var state=state();var old=prepare(state);var fresh=prepare(state);
        assertThatThrownBy(()->state.consume(old.token(),true,"APP",now)).isInstanceOf(Failure.class);
        state.select(new Selection("APP","OTHER"));assertThatThrownBy(()->state.consume(fresh.token(),true,"APP",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->prepare(state)).isInstanceOf(Failure.class);
    }
    @Test void runningBlocksReconfigurationAndSecondRequestButFinishAllowsNewPreview(){
        var state=state();var preview=prepare(state);state.consume(preview.token(),true,"APP",now);
        assertThatThrownBy(()->state.select(null)).isInstanceOf(Failure.class);assertThatThrownBy(()->prepare(state)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.consume(preview.token(),true,"APP",now)).isInstanceOf(Failure.class);
        state.finish();assertThat(prepare(state).token()).isNotEqualTo(preview.token());
    }
    @Test void concurrentSubmissionsProduceExactlyOneRunningRequest() throws Exception {
        var state=state();var preview=prepare(state);var accepted=new AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)){
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<24;i++)futures.add(executor.submit(()->{try{state.consume(preview.token(),true,"APP",now);accepted.incrementAndGet();}catch(Failure expected){}}));
            for(var future:futures)future.get();
        }
        assertThat(accepted).hasValue(1);
    }
    @Test void sourcesAreNotTruncatedAndUnicodeAndSqlLikeTextRemainData(){
        String raw="function f return varchar2 as begin return '日本語 한국어 中文 <script> SELECT AI runsql'; end;\r\n";
        assertThat(AiAssistant.source(definition(raw))).contains(raw);
        assertThatThrownBy(()->AiAssistant.source(definition("가".repeat(AiAssistant.MAX_SOURCE)))).isInstanceOf(Failure.class);
        for(String rawWrapped:List.of("function f wrapped\na00000\n","FUNCTION F\nwrapped\na00000","wrapped\na00000"))
            assertThatThrownBy(()->AiAssistant.source(definition(rawWrapped))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->AiAssistant.source(definition(""))).isInstanceOf(Failure.class);
    }
    @Test void wholePackageIsExplicitAndSourcePromptCannotOverrideAction(){
        var definition=new RoutineSource.Definition("APP","P","F","PACKAGE",List.of(new RoutineSource.Section("PACKAGE","package p as function f return number; end;"),new RoutineSource.Section("PACKAGE BODY","package body p as end;")));
        var state=state();var preview=state.prepare("APP",profile(),"APP.P.F",AiAssistant.source(definition),true,"ja",now);
        var prompt=AiAssistant.prompt(state.consume(preview.token(),true,"APP",now));
        assertThat(prompt).startsWith("Explain this Oracle PL/SQL source in Japanese.").contains("package p as","package body p as","untrusted data","APP.P.F");
        assertThat(preview.packageSource()).isTrue();
        for(var entry:Map.of("ko","Korean","en","English","zh","Simplified Chinese").entrySet())assertThat(AiAssistant.prompt(new Draft(preview,"APP",entry.getKey()))).contains("in "+entry.getValue());
    }
    @Test void generationUsesOnlyBoundChatAndRefusesAnExistingConversation(){
        String sql=AiAssistantRepository.generateSql("C##CLOUD$SERVICE");
        assertThat(sql).contains("\"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\".GET_CONVERSATION_ID IS NOT NULL","RAISE_APPLICATION_ERROR(-20051","prompt => ?","profile_name => ?","action => 'chat'","\"conversation\":false")
                .doesNotContain("SET_PROFILE","SET_ATTRIBUTE","CREATE_CONVERSATION","runsql","EXECUTE IMMEDIATE");
    }
    @Test void backendNeverLogsSourcesOrStoresDurableSettings() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AiAssistantRepository.java"));
        assertThat(repository).contains("setCharacterStream(2","Types.CLOB","setQueryTimeout(90)","result.free()").doesNotContain("logger","getPassword","jdbc.update(","CREATE TABLE","SET_ATTRIBUTE");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/AiAssistantService.java"));
        assertThat(service).contains("before.equals(now)","finally{state.finish();}","finally{source.clear();}","functions.detail(session,schema,reference)");
        String controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/AiAssistantController.java"));
        assertThat(controller).contains("no-store","record Generate(String token,boolean consent)","CredentialCatalogRepository.error(ex)").doesNotContain("getMessage(),ex","Logger");
    }
    @Test void realTemplatesRenderInFourLanguagesAndEscapeAllDatabaseValues(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String lang:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(lang));context.setVariable("info",new DatabaseInfo("<script>","APP","LOW","DB"));context.setVariable("schemas",List.of("APP"));context.setVariable("selectedSchema","APP");
            context.setVariable("_csrf",Map.of("token","template-test-token","headerName","X-CSRF-TOKEN"));context.setVariable("activePage","assistant");context.setVariable("languageReturn","/ai-assistant");
            String html=engine.process("ai-assistant",context);assertThat(html).contains("data-assistant-settings","&lt;script&gt;","value=\"/ai-assistant\"").doesNotContain("??assistant.","th:","> <script>");
            assertThat(html.split("value=\"/ai-assistant\"",-1)).hasSize(4);
            context.setVariable("activePage","functions");context.setVariable("routineName","\"APP\".\"F\"");
            html=engine.process("functions",context);assertThat(html).contains("data-function-explain","data-ae-consent","data-ae-source-details").doesNotContain("??assistant.","th:");
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ai-assistant")).isEqualTo("/ai-assistant");
    }
}
