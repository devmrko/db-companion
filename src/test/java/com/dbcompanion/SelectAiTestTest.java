package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.AiAssistant.*;
import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.repository.AiAssistantRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class SelectAiTestTest {
    final Instant now=Instant.parse("2026-09-21T05:00:00Z");
    final Selection selection=new Selection("APP","PROFILE");
    final Profile profile=new Profile(selection,"oci","model","fingerprint");
    SelectAiTest.State state(){var state=new SelectAiTest.State();state.select(selection);return state;}
    SelectAiTest.Prepared prepare(SelectAiTest.State state){return state.prepare("APP",profile,Action.SQL,"사용자의 권역은?","ko",now);}
    @Test void testSelectionIsIndependentAndLoginScoped(){
        var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));
        session.assistant().select(new Selection("APP","ASSISTANT"));session.aiTest().select(selection);
        session.selectSchema("OTHER");assertThat(session.aiTest().selected()).isEqualTo(selection);
        assertThat(session.assistant().selected().name()).isEqualTo("ASSISTANT");
        assertThat(new DatabaseSession(session.info(),List.of("APP")).aiTest().selected()).isNull();
    }
    @Test void cacheReusesProfilesAndDetailAndExplicitRefreshInvalidatesBoth(){
        var state=state();var calls=new AtomicInteger();
        java.util.function.Supplier<List<Choice>> loader=()->{calls.incrementAndGet();return List.of(new Choice("PROFILE","oci","model"));};
        state.profiles(false,loader);state.profiles(false,loader);assertThat(calls).hasValue(1);
        var detail=new SelectAiTest.Detail(new AiProfile("PROFILE","ENABLED","description","date","1","date"),List.of(new AiProfileAttribute("model","x")));
        assertThat(state.detail("PROFILE",()->detail)).isSameAs(detail);
        assertThat(state.detail("PROFILE",()->{throw new AssertionError("Unexpected load");})).isSameAs(detail);
        var preview=prepare(state);state.profiles(true,loader);assertThat(calls).hasValue(2);
        assertThatThrownBy(()->state.consume(preview.preview().token(),true,"APP",now)).isInstanceOf(Failure.class);
        var detailCalls=new AtomicInteger();state.detail("PROFILE",()->{detailCalls.incrementAndGet();return detail;});assertThat(detailCalls).hasValue(1);
    }
    @Test void tokenRequiresConsentAndIsSingleUseAfterSuccessOrFailure(){
        var state=state();var prepared=prepare(state);String token=prepared.preview().token();
        assertThatThrownBy(()->state.consume(token,false,"APP",now)).isInstanceOf(Failure.class);
        assertThat(state.consume(token,true,"APP",now)).isEqualTo(prepared);
        assertThat(state.running()).isTrue();state.finish(null);
        assertThatThrownBy(()->state.consume(token,true,"APP",now)).isInstanceOf(Failure.class);
        assertThat(state.running()).isFalse();
    }
    @Test void expiryOwnerSelectionCancelAndNewPreviewInvalidateOldTokens(){
        for(String change:List.of("expiry","owner","selection","cancel","preview","refresh")){
            var state=state();var prepared=prepare(state);String token=prepared.preview().token();
            switch(change){case "selection"->state.select(null);case "cancel"->state.cancel(token);case "preview"->prepare(state);case "refresh"->state.profiles(true,List::of);}
            assertThatThrownBy(()->state.consume(token,true,change.equals("owner")?"OTHER":"APP",change.equals("expiry")?now.plusSeconds(600):now)).isInstanceOf(Failure.class);
            assertThat(state.running()).isFalse();
        }
    }
    @Test void exactlyOneConcurrentRequestAndRunningBlocksChanges() throws Exception {
        var state=state();String token=prepare(state).preview().token();var count=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<24;i++)tasks.add(executor.submit(()->{try{state.consume(token,true,"APP",now);count.incrementAndGet();}catch(Failure expected){}}));
            for(var task:tasks)task.get();
        }
        assertThat(count).hasValue(1);
        assertThatThrownBy(()->state.select(selection)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->prepare(state)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.profiles(true,List::of)).isInstanceOf(Failure.class);
        state.cancel(token);assertThat(state.running()).isTrue();state.finish(null);
        assertThat(prepare(state).preview().token()).isNotEqualTo(token);
    }
    @Test void oneLatestResultIsKeptAndNotAffectedByProfileRefresh(){
        var state=state();var result=new SelectAiTest.Outcome("id",Action.CHAT,profile,"question",now,10,"answer",null,null,"complete");
        state.finish(result);state.profiles(true,List::of);assertThat(state.latest()).isEqualTo(result);
        state.finish(null);assertThat(state.latest()).isNull();
    }
    @Test void promptCannotBeginWithUserSuppliedSelectAiAndDoesNotTruncate(){
        String input="SELECT AI RUNSQL DELETE FROM x; 日本語 한국어 中文 <script>";
        for(var action:Action.values()){
            String prompt=SelectAiTest.prompt(action,input,"ko");assertThat(prompt).endsWith(input).doesNotStartWith("SELECT AI");
            assertThat(SelectAiTest.prompt(action,"가".repeat(16000),"ko")).endsWith("가".repeat(16000));
        }
        assertThat(SelectAiTest.prompt(Action.CHAT,"질문","ja")).contains("Japanese");
        for(String inputBad:Arrays.asList(null,""," ","a\0b","가".repeat(16001)))assertThatThrownBy(()->SelectAiTest.question(inputBad)).isInstanceOf(Failure.class);
    }
    @Test void generatedCallsHaveOnlyThreeLiteralActionsAndNeverExecuteReturnedSql(){
        for(var action:Action.values()){
            String sql=AiAssistantRepository.generateSql("C##CLOUD$SERVICE",action);
            assertThat(sql).contains("GET_CONVERSATION_ID IS NOT NULL","prompt => ?","profile_name => ?","\"conversation\":false",
                    "action => '"+(action==Action.SQL?"showsql":action==Action.PROMPT?"showprompt":"chat")+"'").doesNotContain("runsql","narrate","SET_PROFILE","SET_ATTRIBUTE","EXECUTE IMMEDIATE");
        }
        assertThatThrownBy(()->AiAssistantRepository.generateSql("OWNER",null)).isInstanceOf(NullPointerException.class);
    }
    @Test void actualTemplatesRenderInFourLanguagesWithoutSchemaSelectorAndEscapeNames(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String lang:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(lang));context.setVariable("info",new DatabaseInfo("<script>","APP","LOW","DB"));
            context.setVariable("_csrf",Map.of("token","template-test-token","headerName","X-CSRF-TOKEN"));context.setVariable("activePage","ai-test");context.setVariable("languageReturn","/ai-test");
            String html=engine.process("ai-test",context);
            assertThat(html).contains("data-ai-test","data-test-dialog","data-test-consent","&lt;script&gt;","value=\"/ai-test\"")
                    .contains("data-test-execute","data-test-execution-consent","data-test-showprompt","data-test-review-dialog","data-test-review-consent",
                        "data-test-evidence-enabled","data-test-evidence-routes","data-test-request-evidence","data-test-prompt-evidence",
                        "data-inspect-load","data-inspect-body","data-test-prompt-inspection","data-test-sql-references").doesNotContain("??aitest.","??inspect.","th:","data-schema-select");
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ai-test")).isEqualTo("/ai-test");
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ai-test?secret=x")).isEqualTo("/login");
    }
}
