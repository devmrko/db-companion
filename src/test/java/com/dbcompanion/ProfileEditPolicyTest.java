package com.dbcompanion;

import com.dbcompanion.common.db.ProfileEditPolicy;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileEdit.Target;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ProfileEditPolicyTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Target target=new Target("APP","P","additional_instructions");
    Map<String,Object> data(String id,String instruction) {
        return Map.of("exists",true,"profile",Map.of("PROFILE_ID",id,"STATUS","ENABLED"),
                "attributes",Map.of("additional_instructions",instruction,"model","MODEL"));
    }
    @Test void onlyOwnerAndCurrentSchemaMayEdit() {
        assertThatCode(()->ProfileEditPolicy.scope("APP","APP",target)).doesNotThrowAnyException();
        assertThatThrownBy(()->ProfileEditPolicy.scope("ADMIN","APP",target)).isInstanceOf(MetadataEditException.class).hasMessageContaining("owner");
        assertThatThrownBy(()->ProfileEditPolicy.scope("APP","OTHER",target)).hasMessageContaining("schema");
        assertThatThrownBy(()->ProfileEditPolicy.scope("APP","APP",new Target("APP","P",null))).isInstanceOf(MetadataEditException.class);
    }
    @Test void ordinaryAttributesAreEditableButCredentialsAndSqlAreNot() {
        for(String name:List.of("additional_instructions","object_list","max_tokens","credential_name","temperature","comments"))
            assertThat(ProfileEditPolicy.editableAttribute(name)).as(name).isTrue();
        for(String name:List.of("password","api_key","private_key","token","access_token","provider_secret","x'); DELETE FROM T;--"))
            assertThat(ProfileEditPolicy.editableAttribute(name)).as(name).isFalse();
    }
    @Test void validatesUtf8LimitWithoutTrimmingOrCutting() {
        for(String value:List.of("x".repeat(32767),"가".repeat(10922),"  line\r\n... 🌏  "))
            assertThatCode(()->ProfileEditPolicy.input(target,value,json)).doesNotThrowAnyException();
        for(String value:List.of("x".repeat(32768),"가".repeat(10923),"\0","\uD800","\uDC00"," ",""))
            assertThatThrownBy(()->ProfileEditPolicy.input(target,value,json)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->ProfileEditPolicy.input(target,null,json)).isInstanceOf(MetadataEditException.class);
    }
    @Test void validatesObjectListButDoesNotGenerateOrRewritePolicy() {
        var objects=new Target("APP","P","object_list");
        assertThatCode(()->ProfileEditPolicy.input(objects,"[{\"owner\":\"APP\",\"name\":\"T\"}]",json)).doesNotThrowAnyException();
        for(String value:List.of("{}","null","[broken","[] trailing"))
            assertThatThrownBy(()->ProfileEditPolicy.input(objects,value,json)).isInstanceOf(MetadataEditException.class);
        assertThatCode(()->ProfileEditPolicy.input(target,"not JSON, retain exactly",json)).doesNotThrowAnyException();
    }
    @Test void versionCoversWholeProfileIdOtherFieldsAndTarget() {
        var original=data("1","original"); String version=ProfileEditPolicy.version(target,original,json);
        assertThatCode(()->ProfileEditPolicy.verifyVersion(target,original,version,json)).doesNotThrowAnyException();
        assertThatThrownBy(()->ProfileEditPolicy.verifyVersion(target,data("2","original"),version,json)).hasMessageContaining("changed");
        assertThatThrownBy(()->ProfileEditPolicy.verifyVersion(target,data("1","changed"),version,json)).hasMessageContaining("changed");
        var other=new LinkedHashMap<>(original);other.put("attributes",Map.of("additional_instructions","original","model","OTHER"));
        assertThat(ProfileEditPolicy.version(target,other,json)).isNotEqualTo(version);
        assertThat(ProfileEditPolicy.version(new Target("OTHER","P","additional_instructions"),original,json)).isNotEqualTo(version);
        assertThat(ProfileEditPolicy.version(new Target("APP","P","model"),original,json)).isNotEqualTo(version);
    }
    @Test void versionIsOrderIndependentAndDoesNotMutateValues() {
        var original=data("1","  preserved\r\n🌏");var reordered=new LinkedHashMap<>(original);
        var attrs=new LinkedHashMap<String,String>();attrs.put("model","MODEL");attrs.put("additional_instructions","  preserved\r\n🌏");
        reordered.put("attributes",attrs);
        assertThat(ProfileEditPolicy.version(target,reordered,json)).isEqualTo(ProfileEditPolicy.version(target,original,json));
        assertThat(ProfileEditPolicy.value(original,target)).isEqualTo("  preserved\r\n🌏");
    }
    @Test void absentProfileOrAttributeIsNotRecreated() {
        assertThatThrownBy(()->ProfileEditPolicy.value(Map.of("exists",false),target)).hasMessageContaining("not found");
        assertThatThrownBy(()->ProfileEditPolicy.value(Map.of("exists",true,"attributes",Map.of()),target)).hasMessageContaining("not found");
        var attributes=new HashMap<String,String>();attributes.put(target.attribute(),null);
        assertThat(ProfileEditPolicy.value(Map.of("exists",true,"attributes",attributes),target)).isNull();
    }
    @Test void statementBindsAllUserInputsAndOnlyCallsAttributeSetter() {
        String sql=ProfileEditPolicy.sql("C##CLOUD$SERVICE");
        assertThat(sql).contains("\"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\".SET_ATTRIBUTE", "v_value VARCHAR2(32767)");
        assertThat(sql.chars().filter(ch->ch=='?').count()).isEqualTo(3);
        assertThat(sql).doesNotContain("CREATE_PROFILE","DROP_PROFILE","EXECUTE IMMEDIATE","COMMIT","TENANT");
    }
    @Test void readbackRequiresFullTextOtherAttributesAndProfileIdentity() {
        var before=data("1","old");var after=data("1","new\n...");
        assertThat(ProfileEditPolicy.readbackMatches(target,before,after,"new\n...",json)).isTrue();
        assertThat(ProfileEditPolicy.readbackMatches(target,before,after,"new\n... ",json)).isFalse();
        assertThat(ProfileEditPolicy.readbackMatches(target,before,data("2","new\n..."),"new\n...",json)).isFalse();
        assertThat(ProfileEditPolicy.readbackMatches(target,before,Map.of("exists",false),"new\n...",json)).isFalse();
        var unexpected=new HashMap<String,Object>(after);
        unexpected.put("attributes",Map.of("additional_instructions","new\n...","model","OTHER"));
        assertThat(ProfileEditPolicy.readbackMatches(target,before,unexpected,"new\n...",json)).isFalse();
        unexpected.put("attributes",Map.of("model","MODEL"));
        assertThat(ProfileEditPolicy.readbackMatches(target,before,unexpected,"new\n...",json)).isFalse();
    }
    @Test void onlyModificationTimestampMayChangeAlongWithTheRequestedAttribute() {
        var before=new HashMap<String,Object>(data("1","old"));var after=new HashMap<String,Object>(data("1","new"));
        before.put("profile",Map.of("PROFILE_ID","1","STATUS","ENABLED","MODIFIED","before"));
        after.put("profile",Map.of("PROFILE_ID","1","STATUS","ENABLED","MODIFIED","after"));
        assertThat(ProfileEditPolicy.readbackMatches(target,before,after,"new",json)).isTrue();
        after.put("profile",Map.of("PROFILE_ID","1","STATUS","DISABLED","MODIFIED","after"));
        assertThat(ProfileEditPolicy.readbackMatches(target,before,after,"new",json)).isFalse();
    }
    @Test void typedReadbackComparisonDoesNotNormalizeInstructionStrings() {
        assertThat(ProfileEditPolicy.equivalent("object_list","[{\"owner\":\"APP\",\"name\":\"T\"}]","[ {\"name\":\"T\",\"owner\":\"APP\"} ]",json)).isTrue();
        assertThat(ProfileEditPolicy.equivalent("object_list","[]","[{}]",json)).isFalse();
        assertThat(ProfileEditPolicy.equivalent("temperature","0.00","0",json)).isTrue();
        assertThat(ProfileEditPolicy.equivalent("temperature","broken","0",json)).isFalse();
        assertThat(ProfileEditPolicy.equivalent("comments","TRUE","true",json)).isTrue();
        assertThat(ProfileEditPolicy.equivalent("comments","YES","true",json)).isFalse();
        assertThat(ProfileEditPolicy.equivalent("additional_instructions","1.0","1",json)).isFalse();
        assertThat(ProfileEditPolicy.equivalent("additional_instructions","a\r\n","a\n",json)).isFalse();
        assertThat(ProfileEditPolicy.equivalent("additional_instructions","null",null,json)).isFalse();
    }
    @Test void typedInputsAreValidatedOnTheServerToo() {
        for(String attr:ProfileEditPolicy.BOOLEANS) {
            assertThatCode(()->ProfileEditPolicy.input(new Target("APP","P",attr),"FALSE",json)).doesNotThrowAnyException();
            assertThatThrownBy(()->ProfileEditPolicy.input(new Target("APP","P",attr),"yes",json)).isInstanceOf(MetadataEditException.class);
        }
        for(String value:List.of("-9223372036854775808","9223372036854775807","42"))
            assertThatCode(()->ProfileEditPolicy.input(new Target("APP","P","seed"),value,json)).doesNotThrowAnyException();
        for(String value:List.of("-9223372036854775809","9223372036854775808","1.5","1e2"))
            assertThatThrownBy(()->ProfileEditPolicy.input(new Target("APP","P","seed"),value,json)).isInstanceOf(MetadataEditException.class);
        for(String value:List.of("-1","0","1.5","NaN"))
            assertThatThrownBy(()->ProfileEditPolicy.input(new Target("APP","P","max_tokens"),value,json)).isInstanceOf(MetadataEditException.class);
        for(String value:List.of("-0.1","NaN","Infinity"))
            assertThatThrownBy(()->ProfileEditPolicy.input(new Target("APP","P","temperature"),value,json)).isInstanceOf(MetadataEditException.class);
        assertThatCode(()->ProfileEditPolicy.input(new Target("APP","P","temperature"),"0.25",json)).doesNotThrowAnyException();
    }
}
