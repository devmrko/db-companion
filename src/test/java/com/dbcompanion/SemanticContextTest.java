package com.dbcompanion;

import com.dbcompanion.common.db.ProfileEditPolicy;
import com.dbcompanion.model.ProfileEdit.Target;
import com.dbcompanion.model.SemanticContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SemanticContextTest {
    @Test void versionGateUsesFullNumericVersionAndFailsClosed() {
        for (String v : List.of("23.26.4.1.0", "23.26.4.2.0", "23.26.10.0.0", "26.1.0.0.0"))
            assertThat(SemanticContext.versionSupported(v)).isTrue();
        for (String v : List.of("", "26ai", "23.26.4.0.0", "23.26.3.0.0", "19.26.0.0.0", "23.26.4.1", "999999999999.0.0.0.0"))
            assertThat(SemanticContext.versionSupported(v)).isFalse();
        assertThat(SemanticContext.versionSupported(null)).isFalse();
    }
    @Test void validatesSourceArraysIncludingDetachButRejectsMalformedOrDuplicateNames() {
        assertThat(SemanticContext.sources("[]").size()).isZero();
        assertThat(SemanticContext.sources("[\"TERMS\",\"POLICY\"]").size()).isEqualTo(2);
        for (String bad : List.of("null", "{}", "[null]", "[1]", "[\"A\",\"A\"]", "[\"owner.A\"]", "[] {}"))
            assertThatThrownBy(() -> SemanticContext.sources(bad)).isInstanceOf(RuntimeException.class);
    }
    @Test void customConfigRequiresMatchingScopeAndExplicitConsent() {
        String config="{\"scope\":\"global\",\"global_callback_func\":\"APP.PKG.CONTEXT\"}";
        assertThat(SemanticContext.validate(new SemanticContext.Create("APP","TERMS","custom",config,"",true)).isObject()).isTrue();
        assertThatThrownBy(() -> SemanticContext.validate(new SemanticContext.Create("APP","TERMS","custom",config,"",false))).isInstanceOf(RuntimeException.class);
        for (String bad : List.of("{}",config.replace("global\"", "all\""), config.replace("APP.PKG.CONTEXT", "F;END;"), config.replace("}",",\"params\":{\"password\":\"x\"}}")))
            assertThatThrownBy(() -> SemanticContext.validate(new SemanticContext.Create("APP","TERMS","custom",bad,"",true))).isInstanceOf(RuntimeException.class);
    }
    @Test void addingAttributePreservesAllOtherProfileStateAndOptimisticVersion() {
        var target=new Target("APP","PROFILE","semantic_source");var json=new JsonMapper();
        Map<String,Object> before=Map.of("exists",true,"profile",Map.of("PROFILE_ID","1"),"attributes",Map.of("provider","openai"));
        Map<String,Object> after=Map.of("exists",true,"profile",Map.of("PROFILE_ID","1"),"attributes",Map.of("provider","openai","semantic_source","[\"TERMS\"]"));
        assertThat(ProfileEditPolicy.value(before,target)).isEqualTo("[]");
        assertThat(ProfileEditPolicy.readbackMatches(target,before,after,"[\"TERMS\"]",json)).isTrue();
        assertThatThrownBy(() -> ProfileEditPolicy.verifyVersion(target,after,ProfileEditPolicy.version(target,before,json),json)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ProfileEditPolicy.value(before,new Target("APP","PROFILE","model"))).isInstanceOf(RuntimeException.class);
    }
}
