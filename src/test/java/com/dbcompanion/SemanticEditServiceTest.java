package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.SemanticContextRepository;
import com.dbcompanion.service.SemanticContextService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class SemanticEditServiceTest {
    final SessionDataSource source=new SessionDataSource();
    final Map<String,Object> current=new TreeMap<>(Map.of("exists",true,"name","TERMS","type","CUSTOM","status","ENABLED","description","", "attributes",Map.of("scope","global","global_callback_func","APP.CONTEXT")));
    int writes;
    final SemanticContextRepository repository=new SemanticContextRepository(new JdbcTemplate(source)){
        @Override public SemanticContext.Capability capability(){return new SemanticContext.Capability("23.26.4.1.0",true,"supported","API_OWNER");}
        @Override public Map<String,Object> snapshot(String owner,String name){return new TreeMap<>(current);}
        @Override public void update(String owner,String name,String operation,String value){writes++;current.put("status",operation.equals("ENABLE")?"ENABLED":"DISABLED");}
    };
    final SemanticContextService service=new SemanticContextService(source,repository,SemanticChangeTest.immediate(source));
    PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","TEST"),List.of("APP","OTHER")));return s;}
    SemanticContext.Edit edit(String version,String operation,String attrs){return new SemanticContext.Edit("APP","TERMS",operation,attrs,version);}
    @Test void stalePreviewCannotUpdateAndConsentPlanIsBoundToCurrentFingerprint(){try(var s=session()){
        var form=service.editForm(s,"APP","TERMS");var plan=service.editPreview(s,edit(form.version(),"DISABLE",form.attributes()));
        current.put("description","external change");
        assertThatThrownBy(()->service.editApply(s,plan)).isInstanceOf(RuntimeException.class);assertThat(writes).isZero();
    }}
    @Test void statusChangeReadsBackActualState(){try(var s=session()){
        var f=service.editForm(s,"APP","TERMS");var p=service.editPreview(s,edit(f.version(),"DISABLE",f.attributes()));
        assertThat(service.editApply(s,p).verified()).isTrue();assertThat(writes).isEqualTo(1);assertThat(current.get("status")).isEqualTo("DISABLED");
    }}
    @Test void scopeChangesAndSecretParamsAreRejectedBeforeNativeCalls(){try(var s=session()){
        var f=service.editForm(s,"APP","TERMS");
        for(String attrs:List.of("{\"scope\":\"object\",\"object_callback_func\":\"APP.OTHER\"}","{\"scope\":\"global\",\"global_callback_func\":\"APP.CONTEXT\",\"params\":{\"password\":\"hidden\"}}"))
            assertThatThrownBy(()->service.editPreview(s,edit(f.version(),"ATTRIBUTES",attrs))).isInstanceOf(RuntimeException.class);
        assertThat(writes).isZero();
    }}
    @Test void foreignBrowsingCannotReadOrEditSources(){try(var s=session()){
        s.metadata().selectSchema("OTHER");
        assertThatThrownBy(()->service.editForm(s,"OTHER","TERMS")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.editForm(s,"APP","TERMS")).isInstanceOf(RuntimeException.class);assertThat(writes).isZero();
    }}
    @Test void nativeQuotedCallbackNamesRoundTripThroughAttributeEditor(){try(var s=session()){
        current.put("attributes",Map.of("scope","global","global_callback_func","\"APP\".CONTEXT"));
        var form=service.editForm(s,"APP","TERMS");assertThat(form.attributes()).contains("APP.CONTEXT").doesNotContain("\\\"APP\\\"");
        var input=edit(form.version(),"ATTRIBUTES","{\"scope\":\"global\",\"global_callback_func\":\"APP.CONTEXT\",\"params\":{\"term_set\":\"v2\"}}");
        assertThat(service.editPreview(s,input).input()).isEqualTo(input);assertThat(writes).isZero();
    }}
}
