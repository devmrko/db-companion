package com.dbcompanion;

import com.dbcompanion.controller.AiSetupController;
import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.service.AiSetupService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.assertThat;

class AiSetupTest {
    @Test void historyReadinessDoesNotTreatUnknownAsMissingOrReady() {
        assertThat(com.dbcompanion.service.AiSetupHistoryService.storage("READY")).isEqualTo(AiSetupService.State.CONFIGURED);
        for(String value:List.of("MISSING","MISMATCH","CONFLICT"))
            assertThat(com.dbcompanion.service.AiSetupHistoryService.storage(value)).isEqualTo(AiSetupService.State.ACTION);
        assertThat(com.dbcompanion.service.AiSetupHistoryService.storage("UNAVAILABLE")).isEqualTo(AiSetupService.State.UNKNOWN);
        assertThat(com.dbcompanion.service.AiSetupHistoryService.enabled(null)).isEqualTo(AiSetupService.State.UNKNOWN);
        assertThat(com.dbcompanion.service.AiSetupHistoryService.enabled(false)).isEqualTo(AiSetupService.State.ACTION);
    }
    @Test void observationsRequireMatchingOwnerProfileAndExecutionId() {
        var profile=new com.dbcompanion.model.AiAssistant.Profile(new com.dbcompanion.model.AiAssistant.Selection("APP","P"),"oci","model","v1");
        var outcome=new com.dbcompanion.model.SelectAiTest.Outcome("r1",com.dbcompanion.model.SelectAiTest.Action.SQL,
                profile,"synthetic",java.time.Instant.EPOCH,1,"SELECT 1 FROM DUAL",null,null,"complete");
        var execution=new com.dbcompanion.model.SelectAiTest.ExecutionResult("r1",null,"failed","ORA-01031",1);
        var rows=AiSetupService.observations("APP","P",java.util.Arrays.asList(outcome,null,outcome),execution);
        assertThat(rows).extracting(AiSetupService.Observation::state).containsExactly("RETURNED","FAILED");
        assertThat(rows).extracting(AiSetupService.Observation::kind).containsExactly("SQL","EXECUTION");
        assertThat(AiSetupService.observations("OTHER","P",List.of(outcome),execution)).isEmpty();
        assertThat(AiSetupService.observations("APP","OTHER",List.of(outcome),execution)).isEmpty();
        assertThat(AiSetupService.observations("APP","P",List.of(outcome),
                new com.dbcompanion.model.SelectAiTest.ExecutionResult("old",null,null,null,1))).hasSize(1);
    }
    @Test void credentialLookupFailureIsUnknownAndThreadBindingIsCleared() {
        var source = new com.dbcompanion.common.db.SessionDataSource();
        var database = new com.dbcompanion.service.DatabaseService(null,source,null,null,null) {
            @Override public ProfilePage profiles(com.dbcompanion.common.db.PoolSession s, String name) {
                return new ProfilePage(List.of(new com.dbcompanion.model.AiProfile("P","ENABLED","","","","")),
                        List.of(new AiProfileAttribute("credential_name","C")));
            }
        };
        var repository = new com.dbcompanion.repository.ProfileEditRepository(new org.springframework.jdbc.core.JdbcTemplate(source)) {
            @Override public List<String> credentials() { throw new org.springframework.dao.PermissionDeniedDataAccessException("synthetic denial",null); }
        };
        try(var session = new OntologyReadCacheTest().session()) {
            var checks = new AiSetupService(database,null,source,repository).diagnose(session,"P","nl2sql");
            assertThat(checks).filteredOn(c -> List.of("credential","external","execution").contains(c.key()))
                    .hasSize(3).allMatch(c -> c.state() == AiSetupService.State.UNKNOWN);
            source.bind(session.pool(),"APP"); source.clear();
        }
    }
    @Test void allLanguagesRenderPurposeAndEscapedProfileLinks() {
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String base,java.util.Map<String,Object> params) { return ""; }
        });
        for(String language : List.of("ko","en","ja","zh-CN")) {
            var context = new org.thymeleaf.context.Context(java.util.Locale.forLanguageTag(language));
            context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","synthetic"));
            context.setVariable("info",new com.dbcompanion.model.DatabaseInfo("APP","APP","LOW","SYNTHETIC"));
            context.setVariable("activePage","ai-setup"); context.setVariable("languageReturn","/ai-setup");
            context.setVariable("purpose","nl2sql"); context.setVariable("profile","P&<test>");
            context.setVariable("purposes",com.dbcompanion.service.AiSetupCatalog.purposes());
            context.setVariable("profiles",List.of(new com.dbcompanion.model.AiProfile("P&<test>","ENABLED","","","","")));
            context.setVariable("checks",List.of(new AiSetupService.Check("model",AiSetupService.State.CONFIGURED,"/ai-profiles"),
                    new AiSetupService.Check("execution",AiSetupService.State.UNKNOWN,"/ai-test")));
            context.setVariable("observations",List.of(new AiSetupService.Observation("SQL","r1",java.time.Instant.EPOCH,"RETURNED",null)));
            context.setVariable("savedEvidence",List.of(new com.dbcompanion.model.SetupEvidence.Group("conversation","RECORDED",
                    List.of(new com.dbcompanion.model.SetupEvidence.Item("<script>unsafe</script>","2026-11-19","showsql"))),
                    new com.dbcompanion.model.SetupEvidence.Group("problem","UNKNOWN",List.of())));
            context.setVariable("historyReview",new com.dbcompanion.model.SetupHistoryReview.Result("RECORDED",List.of(
                    new com.dbcompanion.model.SetupHistoryReview.Review("safe-id","2026-11-19","DIFFERENT",List.of("comments"),List.of("comments"),true,
                            new com.dbcompanion.model.SetupHistoryReview.Recovery("note","retest"),"MATCH"))));
            context.setVariable("historyChecks",List.of("records","conversation","conversationView","profileStore","profileAudit","profileCollect","auditSource","auditStore","auditCollector","auditSchedule","objectHistory").stream()
                    .map(key->new com.dbcompanion.service.AiSetupHistoryService.Check(key,AiSetupService.State.UNKNOWN,"/ai-profiles","synthetic")).toList());
            assertThat(engine.process("ai-setup",context)).contains("app-setup-CONFIGURED","app-setup-UNKNOWN",
                    "/ai-profiles/detail?profile=P%26%3Ctest%3E", "P&amp;&lt;test&gt;")
                    .contains("data-saved-evidence", "&lt;script&gt;unsafe&lt;/script&gt;", "data-history-review", "safe-id", "retest")
                    .doesNotContain("??", "th:", "<test>", "<script>unsafe</script>");
        }
    }
    @Test void placeholderRowsAndDisabledProfileAreNotReady() {
        var checks = AiSetupService.configuration(List.of(new AiProfileAttribute("provider",null),
                new AiProfileAttribute("model"," "),new AiProfileAttribute("object_list","[]")),false);
        assertThat(checks).allMatch(c -> c.state() == AiSetupService.State.ACTION);
    }
    @Test void malformedOrUnstructuredObjectListDoesNotCountAsConfigured() {
        for (String value : List.of("null", "{}", "[1]", "[{}]", "[null]", "broken", "[]",
                "[{\"owner\":\"APP\",\"name\":\" \"}]"))
            assertThat(AiSetupService.arrayConfigured(value)).as(value).isFalse();
        assertThat(AiSetupService.arrayConfigured("[{\"owner\":\"APP\",\"name\":\"SALES\"}]")).isTrue();
    }
    @Test void configurationEvidenceDoesNotIncludeExecutionSuccess() {
        var checks = AiSetupService.configuration(List.of(new AiProfileAttribute("provider","oci"),
                new AiProfileAttribute("model","synthetic-model"),
                new AiProfileAttribute("object_list","[{\"owner\":\"APP\",\"name\":\"SALES\"}]")),true);
        assertThat(checks).allMatch(c -> c.state() == AiSetupService.State.CONFIGURED);
        assertThat(checks).noneMatch(c -> c.key().equals("execution") || c.key().equals("external"));
    }
    @Test void missingSessionDoesNotQueryDatabaseOrCreateSession() {
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        assertThat(new AiSetupController(null,null).page("nl2sql","APP_PROFILE",request,response,new ExtendedModelMap()))
                .isEqualTo("redirect:/login");
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void foreignSchemaStopsBeforeAnyProfileRead() {
        try(var session = new OntologyReadCacheTest().session()) {
            session.metadata().selectSchema("OTHER");
            var request = new MockHttpServletRequest(); request.getSession().setAttribute(com.dbcompanion.common.db.PoolSession.ATTRIBUTE,session);
            var model = new ExtendedModelMap();
            assertThat(new AiSetupController(null,null).page("nl2sql","APP_PROFILE",request,new MockHttpServletResponse(),model))
                    .isEqualTo("ai-setup");
            assertThat(model.get("ownerRequired")).isEqualTo(true);
            assertThat(model.get("checks")).isEqualTo(List.of());
        }
    }
}
