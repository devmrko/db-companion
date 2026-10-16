package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.controller.AccountSetupController;
import com.dbcompanion.model.*;
import com.dbcompanion.model.AccountSetup.*;
import com.dbcompanion.service.AccountSetupService;
import com.dbcompanion.service.ScottDemoService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class AccountSetupTest {
    @Test void setupTemplatesRenderTranslatedAndHideAccountFormFromNonAdmins(){
        var resolver=new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new org.thymeleaf.spring6.SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context,String base,java.util.Map<String,Object> parameters){return "";}});
        for(var locale:List.of(java.util.Locale.KOREAN,java.util.Locale.ENGLISH,java.util.Locale.JAPANESE,java.util.Locale.SIMPLIFIED_CHINESE)){
            var context=new org.thymeleaf.context.Context(locale);context.setVariable("info",new DatabaseInfo("ADMIN","ADMIN","LOW","DB<script>"));context.setVariable("accountAdmin",true);context.setVariable("activePage","accounts");context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","synthetic"));
            context.setVariable("instructions",com.dbcompanion.controller.AdminHelpController.instructions("APP_TEST"));
            for(var page:List.of("account-setup","scott-demo","admin-help","function-repair"))assertThat(engine.process(page,context)).contains("DB&lt;script&gt;").doesNotContain("??","th:","DB<script>");
            context.setVariable("accountAdmin",false);assertThat(engine.process("account-setup",context)).doesNotContain("data-as-password","data-as-create");
        }
    }
    PoolSession session(String user){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo(user,user,"LOW","DB"),List.of(user)));return s;}
    @Test void fixedPresetsNeverGrantAdministrativeOrCrossSchemaPrivileges(){
        assertThat(AccountSetupService.privilegeSql("APP_TEST","DDS")).containsExactly("GRANT CREATE DATA GRANT TO \"APP_TEST\"","GRANT SELECT ON SYS.DBA_DATA_ROLES TO \"APP_TEST\"");
        assertThat(AccountSetupService.privilegeSql("APP_TEST","DDS_POLICY")).containsExactly("GRANT ADMINISTER ANY DATA GRANT ON SCHEMA \"APP_TEST\" TO \"APP_TEST\"");
        assertThat(AccountSetupService.privilegeSql("APP_TEST","VPD")).containsExactly("GRANT EXECUTE ON SYS.DBMS_RLS TO \"APP_TEST\"");
        assertThat(AccountSetup.grants(new Input("APP_LIMITED",Preset.LIMITED))).containsExactly("GRANT CREATE SESSION TO \"APP_LIMITED\"");
        for(var preset:Preset.values()){
            String sql=String.join("\n",AccountSetup.plan(new Input("APP_TEST",preset)).statements());
            assertThat(sql).doesNotContain("DBA"," ANY ","UNLIMITED","ACL","WITH ADMIN OPTION","WITH GRANT OPTION","DROP","REPLACE");
            if(preset!=Preset.LIMITED)assertThat(sql).contains("QUOTA 100M ON DATA");
        }
    }
    @Test void identifiersPasswordsAndPreviewAreBoundedWithoutExposingPasswords(){
        for(String name:List.of("ADMIN","SYS","SYSTEM","PUBLIC","ANONYMOUS","A;DROP USER X","A\"","a_user","A.B","A".repeat(31)))assertThatThrownBy(()->AccountSetup.validate(new Input(name,Preset.WORKSPACE))).isInstanceOf(AiAssistant.Failure.class);
        for(String password:List.of("short","A".repeat(31),"Invalid\"Secret123","Invalid'Pass123","Invalid\nPass123","lowercase12345"))assertThatThrownBy(()->AccountSetup.password(password)).isInstanceOf(AiAssistant.Failure.class);
        String secret="Synthetic_Ab123!";AccountSetup.password(secret);assertThat(new Apply("t",true,secret).toString()).doesNotContain(secret);
        var p=AccountSetup.plan(new Input("APP_TEST",Preset.WORKSPACE));
        AccountSetup.require(p,new Apply(p.token(),true,null));
        assertThatThrownBy(()->AccountSetup.require(p,new Apply(p.token(),false,null))).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->AccountSetup.require(new Plan(p.token(),p.input(),p.statements(),Instant.EPOCH),new Apply(p.token(),true,null))).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void adminOnlyAndDemoOwnerChecksPrecedeDatabaseWork(){
        try(var user=session("APP_TEST");var admin=session("ADMIN")){
            assertThatThrownBy(()->new AccountSetupService().preview(user,new Input("NEW_USER",Preset.LIMITED))).isInstanceOf(AiAssistant.Failure.class);
            assertThatThrownBy(()->new AccountSetupService().privilegePreview(user,"APP_TEST","TEXT")).isInstanceOf(AiAssistant.Failure.class);
            assertThatThrownBy(()->new ScottDemoService().status(admin)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(ScottDemoService.owner(user)).isEqualTo("APP_TEST");
        }
    }
    @Test void existingAccountGrantsAreFixedAndOneShot(){
        assertThat(AccountSetupService.privilegeSql("APP_TEST","RDF")).containsExactly("GRANT CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SEQUENCE, CREATE TRIGGER, CREATE TYPE TO \"APP_TEST\"");
        assertThat(AccountSetupService.privilegeSql("APP_TEST","TEXT")).containsExactly("GRANT EXECUTE ON CTXSYS.CTX_DDL TO \"APP_TEST\"");
        for(String operation:List.of("DBA","ANY","TEXT; DROP USER X"))assertThatThrownBy(()->AccountSetupService.privilegeSql("APP_TEST",operation)).isInstanceOf(AiAssistant.Failure.class);
        int[] writes={0};
        var service=new AccountSetupService(){
            @Override public PrivilegePlan privilegePreview(PoolSession s,String name,String operation){return new PrivilegePlan("t",name,42,operation,privilegeSql(name,operation),Instant.now().plusSeconds(60));}
            @Override public Status privilegeApply(PoolSession s,PrivilegePlan p){writes[0]++;return new Status(p.name(),true,"OPEN",List.of(),List.of("CTXSYS.CTX_DDL:EXECUTE"),0);}
        };
        var controller=new AccountSetupController(service);var request=new MockHttpServletRequest();
        try(var s=session("ADMIN")){
            request.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
            controller.privilegePreview(new AccountSetupController.PrivilegeInput("APP_TEST","TEXT"),request);
            assertThat(controller.privilegeApply(new AccountSetupController.PrivilegeApply("t",false),request).getStatusCode().value()).isEqualTo(409);
            assertThat(controller.privilegeApply(new AccountSetupController.PrivilegeApply("t",true),request).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.privilegeApply(new AccountSetupController.PrivilegeApply("t",true),request).getStatusCode().value()).isEqualTo(409);
            assertThat(writes[0]).isEqualTo(1);
        }
    }
    @Test void adminRemedyOnlyIncludesMissingGrantsAndNeverShrinksAnExhaustedQuota(){
        assertThat(ScottDemoService.adminSql("APP_TEST",List.of(),0)).isEmpty();
        assertThat(ScottDemoService.adminSql("APP_TEST",List.of("CREATE TABLE"),0)).containsExactly("GRANT CREATE TABLE TO \"APP_TEST\";");
        assertThat(ScottDemoService.adminSql("APP_TEST",List.of("DATA quota"),500L*1048576)).containsExactly("ALTER USER \"APP_TEST\" QUOTA 601M ON DATA;");
        assertThat(ScottDemoService.adminSql("A\"B",List.of("CREATE TABLE"),0)).containsExactly("GRANT CREATE TABLE TO \"A\"\"B\";");
        var guide=com.dbcompanion.controller.AdminHelpController.instructions("APP_TEST");
        assertThat(guide).hasSize(9);
        assertThat(guide.stream().map(com.dbcompanion.controller.AdminHelpController.Instruction::sql)).allSatisfy(sql->assertThat(sql).endsWith("TO \"APP_TEST\";").doesNotContain(" ANY ","DBA","UNLIMITED","WITH GRANT"));
    }
    @Test void controllerConsumesCreationAndOnlyRecoversItsCreatedIdentity(){
        int[] creates={0},configures={0};boolean[] fail={true};
        var service=new AccountSetupService(){
            @Override public Plan preview(PoolSession s,Input in){return AccountSetup.plan(in);}
            @Override public long create(PoolSession s,Plan p,String password){creates[0]++;return 42;}
            @Override public Status configure(PoolSession s,Plan p,long id){assertThat(id).isEqualTo(42);configures[0]++;if(fail[0])throw AccountSetup.error(503,"failed");return new Status(p.input().name(),true,"OPEN",List.of("CREATE SESSION"),List.of(),0);}
        };
        var controller=new AccountSetupController(service);var request=new MockHttpServletRequest();
        try(var s=session("ADMIN");var replacement=session("ADMIN")){
            request.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
            var plan=(Plan)controller.preview(new Input("APP_TEST",Preset.LIMITED),request).getBody();
            var input=new Apply(plan.token(),true,"Synthetic_Ab123!");
            assertThat(controller.configure(input,request).getStatusCode().value()).isEqualTo(409);
            assertThat(controller.create(input,request).getStatusCode().value()).isEqualTo(503);
            assertThat(controller.create(input,request).getStatusCode().value()).isEqualTo(409);
            fail[0]=false;assertThat(controller.configure(new Apply(plan.token(),true,null),request).getStatusCode().value()).isEqualTo(200);
            assertThat(creates[0]).isEqualTo(1);assertThat(configures[0]).isEqualTo(2);
            request.getSession().setAttribute(PoolSession.ATTRIBUTE,replacement);
            assertThat(controller.configure(input,request).getStatusCode().value()).isEqualTo(409);
        }
    }
    @Test void sqlErrorsCannotEchoPasswordOrSql(){
        String secret="Synthetic_Ab123!";
        var pool=new HikariDataSource(){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{throw new java.sql.SQLException("CREATE USER IDENTIFIED BY "+secret);}};
        try(var s=new PoolSession(pool,"LOW",()->{})){
            s.initialize(new DatabaseSession(new DatabaseInfo("ADMIN","ADMIN","LOW","DB"),List.of("ADMIN")));
            var p=AccountSetup.plan(new Input("APP_TEST",Preset.LIMITED));
            assertThatThrownBy(()->new AccountSetupService().create(s,p,secret)).isInstanceOf(AiAssistant.Failure.class).hasMessageNotContaining(secret).hasMessageNotContaining("CREATE USER").hasNoCause();
        }
    }
    @Test void bundledDemoHasOnlyFixedNonDestructiveStatementsAndOneShotConsent(){
        var sql=ScottDemoService.statements();
        assertThat(sql.stream().filter(s->s.startsWith("CREATE TABLE"))).hasSize(2);
        assertThat(sql.stream().filter(s->s.startsWith("INSERT INTO DEPT"))).hasSize(4);
        assertThat(sql.stream().filter(s->s.startsWith("INSERT INTO EMP"))).hasSize(14);
        assertThat(String.join("\n",sql)).doesNotContain("DROP ","CREATE USER","GRANT ","WHENEVER","COMMIT");
        var p=new ScottDemoService.Plan("token","APP_TEST",sql,Instant.now().plusSeconds(60));
        ScottDemoService.require(p,"token",true,"APP_TEST");
        assertThatThrownBy(()->ScottDemoService.require(p,"token",false,"APP_TEST")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->ScottDemoService.require(p,"token",true,"OTHER")).isInstanceOf(AiAssistant.Failure.class);
    }
}
