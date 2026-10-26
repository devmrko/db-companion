package com.dbcompanion;

import com.dbcompanion.model.LocalObjectCreation;
import com.dbcompanion.model.LocalObjectCreation.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalObjectCreationTest {
    Input function(String name,String body){return new Input("APP",Kind.FUNCTION,name,"",body);}
    @Test void createsFixedInvokerHeaderWithoutReplace(){
        var sql=LocalObjectCreation.functionSql(function("ECHO","BEGIN RETURN p_input; END;"));
        assertThat(sql).startsWith("CREATE FUNCTION \"APP\".\"ECHO\"(p_input CLOB)").contains("RETURN CLOB AUTHID CURRENT_USER IS").doesNotContain("OR REPLACE");
    }
    @Test void namesAndScriptSeparatorsAreRejected(){
        for(String name:java.util.List.of("A.B","A;DROP","A\"","a"))assertThatThrownBy(()->LocalObjectCreation.validate(function(name,"BEGIN RETURN p_input; END;"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->LocalObjectCreation.validate(function("F","BEGIN RETURN p_input; END;\n/\n"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void credentialPreviewCannotContainSecretOrCustomBody(){
        var in=new Input("APP",Kind.CREDENTIAL,"C","user ' 한글 \\","");LocalObjectCreation.validate(in);
        assertThatThrownBy(()->LocalObjectCreation.validate(new Input("APP",Kind.CREDENTIAL,"C","u","BEGIN NULL; END;"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Apply("token",true,"not-public").toString()).doesNotContain("not-public");
        assertThat(java.util.Arrays.stream(Input.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("secret","password");
    }
    @Test void consentOwnerExpiryAndTokenAreRequired(){
        var in=function("F","BEGIN RETURN p_input; END;");var p=LocalObjectCreation.plan(in,LocalObjectCreation.functionSql(in));
        LocalObjectCreation.require(p,new Apply(p.token(),true,""),"APP","APP");
        assertThatThrownBy(()->LocalObjectCreation.require(p,new Apply(p.token(),false,""),"APP","APP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalObjectCreation.require(p,new Apply("wrong",true,""),"APP","APP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalObjectCreation.require(p,new Apply(p.token(),true,""),"OTHER","APP")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->LocalObjectCreation.require(new Plan(p.token(),in,p.sql(),Instant.EPOCH),new Apply(p.token(),true,""),"APP","APP")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void secretAndUnicodeBoundsAreNotSilentlyTruncated(){
        var p=LocalObjectCreation.plan(new Input("APP",Kind.CREDENTIAL,"C","u",""),"fixed");
        LocalObjectCreation.require(p,new Apply(p.token(),true,"quote' slash\\ 한글"),"APP","APP");
        for(String secret:java.util.List.of(""," ","a".repeat(4001),"\ud800"))assertThatThrownBy(()->LocalObjectCreation.require(p,new Apply(p.token(),true,secret),"APP","APP")).isInstanceOf(RuntimeException.class);
    }
}
