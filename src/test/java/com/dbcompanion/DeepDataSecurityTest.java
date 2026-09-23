package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.DeepDataSecurity.*;
import com.dbcompanion.repository.DeepDataSecurityRepository;
import com.dbcompanion.service.DeepDataSecurityService;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class DeepDataSecurityTest {
    private Dataset dataset(String status){var row=new LinkedHashMap<String,String>();row.put("DATA_ROLE","R");row.put("MAPPED_TO",null);return new Dataset("SYS.DBA_DATA_ROLES",List.of("DATA_ROLE","MAPPED_TO"),List.of(row),status,"","2026-09-18T00:00:00Z");}
    @Test void valuesAreImmutableAndPreserveNulls(){
        var data=dataset("AVAILABLE");assertThat(data.rows().getFirst()).containsEntry("MAPPED_TO",null);
        assertThatThrownBy(()->data.rows().getFirst().put("x","y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->data.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void sessionCachesGlobalListsAndScopedGrantsUntilExplicitRefresh(){
        var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));var calls=new AtomicInteger();
        java.util.function.Supplier<Dataset> load=()->{calls.incrementAndGet();return dataset("AVAILABLE");};
        state.security(Kind.roles,"APP",false,load);state.security(Kind.roles,"OTHER",false,load);assertThat(calls).hasValue(1);
        state.security(Kind.grants,"APP",false,load);state.security(Kind.grants,"OTHER",false,load);assertThat(calls).hasValue(3);
        state.security(Kind.roles,"APP",true,load);state.security(Kind.grants,"APP",false,load);assertThat(calls).hasValue(5);
        var other=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));other.security(Kind.roles,"APP",false,load);assertThat(calls).hasValue(6);
    }
    @Test void knownAccessStateCachesButTransientErrorsDoNot(){
        var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));var calls=new AtomicInteger();
        for(int i=0;i<2;i++)state.security(Kind.roles,"APP",false,()->{calls.incrementAndGet();return dataset("ACCESS_REQUIRED");});assertThat(calls).hasValue(1);
        state.security(Kind.roles,"APP",true,()->dataset("ERROR"));
        state.security(Kind.roles,"APP",false,()->{calls.incrementAndGet();return dataset("AVAILABLE");});assertThat(calls).hasValue(2);
        assertThat(dataset("LIMIT").cacheable()).isFalse();
    }
    @Test void onlyKnownOracleAccessCodesAreEligibleForFallback(){
        assertThat(DeepDataSecurityRepository.accessError(new RuntimeException(new SQLException("raw","",942)))).isTrue();
        var root=new SQLException("outer");root.setNextException(new SQLException("inner","",1031));assertThat(DeepDataSecurityRepository.accessError(root)).isTrue();
        assertThat(DeepDataSecurityRepository.accessError(new SQLException("ORA-00942 text without the code","",904))).isFalse();
        assertThat(DeepDataSecurityRepository.accessError(new SQLException("timeout","",1013))).isFalse();
    }
    @Test void schemaConflictIdentifiersAndLocalLanguageRoute(){
        DeepDataSecurityService.scope("APP","APP");DeepDataSecurityService.identifier("A\".B ' < >");
        assertThatThrownBy(()->DeepDataSecurityService.scope("APP","OTHER")).isInstanceOf(Failure.class);
        for(String name:List.of("","X".repeat(129),"A\0B"))assertThatThrownBy(()->DeepDataSecurityService.identifier(name)).isInstanceOf(IllegalArgumentException.class);
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/security")).isEqualTo("/db/security");
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("//host/db/security")).isEqualTo("/login");
        assertThat(DeepDataSecurityRepository.isTrue("TRUE")).isTrue();assertThat(DeepDataSecurityRepository.isTrue("1")).isTrue();assertThat(DeepDataSecurityRepository.isTrue(null)).isFalse();
    }
    @Test void repositoryIsFixedDictionaryOnlyWithBoundSchemaAndDetailNames() throws Exception{
        String code=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/DeepDataSecurityRepository.java"));
        assertThat(code).contains("WHERE OBJECT_OWNER = ?", "AND OWNER = ? AND GRANT_NAME = ?", "WHERE DATA_ROLE = ?", "GRANTEE_TYPE = 'DATA ROLE'", "ROLE_TYPE = 'DATA ROLE'", "statement.setObject", "FETCH FIRST", "getColumnLabel", "isTrue(row.get(\"CROSS_TABLE_DATA_GRANT\"))");
        assertThat(code).doesNotContain("jdbc.execute(","jdbc.update(","DBMS_","DBA_USERS","password","equals(\"ADMIN\")");
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/service/DeepDataSecurityService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","finally{source.clear();}");
    }
    @Test void fourLanguageRealTemplatesEscapeNamesAndOfferNoSecurityMutations(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,java.util.Map<String,Object> p){return "";}});
        var labels=Map.of("ko","역할 부여","en","Role grants","zh-CN","角色授予","ja","ロール付与");
        labels.forEach((language,label)->{
            var context=new Context(Locale.forLanguageTag(language));context.setVariable("activePage","security");context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));
            context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");
            String html=engine.process("deep-data-security",context);
            assertThat(html).contains(label,"Deep Data Security","value=\"/db/security\"","data-schema").doesNotContain("??dds.","data-dds-save","th:");
            assertThat(html).contains("&lt;script&gt;").doesNotContain("data-schema=\"<script>");
        });
    }
}
