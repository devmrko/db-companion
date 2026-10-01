package com.dbcompanion;

import com.dbcompanion.controller.OrdsApiTestController;
import com.dbcompanion.model.OrdsApiTest.*;
import com.dbcompanion.model.OrdsManagement.Failure;
import com.dbcompanion.service.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static com.dbcompanion.OrdsManagementTest.*;

class OrdsApiTestTest {
    static Request request(String token,Map<String,String> parameters,List<Pair> headers,Auth auth,String body,boolean confirmed){return new Request(token,parameters,List.of(),headers,body,auth,5,confirmed);}
    static Request empty(String token){return request(token,Map.of(),List.of(),new Auth("NONE","",""),"",false);}
    @Test void targetsAreAnOperatorRegistryNotAnOpenProxy(){
        var targets=new OrdsApiTargets("{\"DB\":\"https://api.example.invalid/ords/\"}");assertThat(targets.resolve("DB")).hasHost("api.example.invalid");
        assertThatThrownBy(()->targets.resolve("OTHER")).hasMessage("test.endpointMissing");
        for(String value:List.of("http://example.invalid/ords/","https://u:p@example.invalid/ords/","https://example.invalid/ords/?x=1","https://example.invalid/ords/#x","https://example.invalid/ords/../","https://example.invalid/ords/%2e%2e/","https://127.0.0.1/ords/","https://localhost/ords/","https://[::1]/ords/"))assertThatThrownBy(()->OrdsApiTargets.validate(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrdsApiTargets("{invalid private value")).hasMessage("Invalid ORDS_TEST_ENDPOINTS configuration");
    }
    @Test void onlyPublishedHandlerFromExactMetadataCanBeSelected(){
        var data=catalog();var snap=snapshot(data);var selection=new Selection("APP","demo","items/","GET",snap.revision());var unpublished=snap;
        assertThatThrownBy(()->OrdsApiRoute.resolve(selection,unpublished)).hasMessage("test.notPublished");
        data.get("MODULES").getFirst().put("STATUS","PUBLISHED");snap=snapshot(data);var current=snap;
        assertThatThrownBy(()->OrdsApiRoute.resolve(selection,current)).hasMessage("stale");
        assertThat(OrdsApiRoute.resolve(new Selection("APP","demo","items/","GET",snap.revision()),snap).path()).isEqualTo("app/demo/items/");
        assertThatThrownBy(()->OrdsApiRoute.resolve(new Selection("APP","demo","items/","DELETE",current.revision()),current)).hasMessage("test.handlerMissing");
        data.get("TEMPLATES").getFirst().put("URI_TEMPLATE","items/:id");var params=snapshot(data);assertThat(OrdsApiRoute.resolve(new Selection("APP","demo","items/:id","GET",params.revision()),params).parameters()).containsExactly("id");
        data.get("TEMPLATES").getFirst().put("URI_TEMPLATE","items/*");var wild=snapshot(data);assertThatThrownBy(()->OrdsApiRoute.resolve(new Selection("APP","demo","items/*","GET",wild.revision()),wild)).hasMessage("test.routeUnsupported");
    }
    @Test void parametersStayInsideOnePathSegmentAndQueryValuesAreEncoded(){
        var base=URI.create("https://example.invalid/ords/");var route=new OrdsApiRoute.Route("GET","app/demo/items/:id",List.of("id"));
        assertThat(OrdsApiRoute.uri(base,route,Map.of("id","한 글"),List.of(new Pair("q","a&b"),new Pair("q","x=y"))).toASCIIString()).endsWith("/items/%ED%95%9C%20%EA%B8%80?q=a%26b&q=x%3Dy");
        for(String value:List.of("","..",".","../admin","a/b","%2e%2e","%252f","x\\y","x?y","x#z","x;y","\r\n"))assertThatThrownBy(()->OrdsApiRoute.uri(base,route,Map.of("id",value),List.of())).hasMessage("test.pathInvalid");
        assertThatThrownBy(()->OrdsApiRoute.uri(base,route,Map.of("id","1","url","https://other.invalid"),List.of())).hasMessage("test.pathInvalid");
    }
    @Test void credentialsAreExplicitAndNeverIncludedInRequestToString(){
        var input=request("t",Map.of(),List.of(),new Auth("BASIC","api-user","synthetic-secret"),"",false);
        assertThat(OrdsApiTransport.headers(input).get("Authorization")).isEqualTo("Basic YXBpLXVzZXI6c3ludGhldGljLXNlY3JldA==");
        assertThat(input.toString()).doesNotContain("synthetic-secret","api-user");assertThat(input.auth().toString()).doesNotContain("synthetic-secret");
        var bearer=request("t",Map.of(),List.of(),new Auth("BEARER","","synthetic-token"),"",false);assertThat(OrdsApiTransport.headers(bearer)).containsEntry("Authorization","Bearer synthetic-token");
        assertThat(OrdsApiTransport.headers(empty("t"))).doesNotContainKey("Authorization").doesNotContainKey("Cookie");
        for(String name:List.of("Host","Authorization","cookie","Proxy-Authorization","Content-Length","Connection","Transfer-Encoding","Accept-Encoding"))assertThatThrownBy(()->OrdsApiTransport.headers(request("t",Map.of(),List.of(new Pair(name,"x")),new Auth("NONE","",""),"",false))).hasMessage("test.headerInvalid");
        assertThatThrownBy(()->OrdsApiTransport.headers(request("t",Map.of(),List.of(new Pair("X-Key","x\r\nHost: other")),new Auth("NONE","",""),"",false))).hasMessage("test.requestInvalid");
        assertThatThrownBy(()->OrdsApiTransport.headers(request("t",Map.of(),List.of(new Pair("X-Key","a"),new Pair("x-key","b")),new Auth("NONE","",""),"",false))).hasMessage("test.headerInvalid");
    }
    @Test void mutationsRequireConfirmationAndJsonSizeAndTimeAreBounded(){
        var input=request("t",Map.of(),List.of(),new Auth("NONE","",""),"{}",false);
        assertThatThrownBy(()->OrdsApiTransport.body("POST",input)).hasMessage("test.confirmRequired");assertThatThrownBy(()->OrdsApiTransport.body("GET",input)).hasMessage("test.requestInvalid");
        assertThat(OrdsApiTransport.body("POST",request("t",Map.of(),List.of(),input.auth(),"{\"a\":1}",true))).isNotEmpty();
        for(String body:List.of("not json","{} {}"))assertThatThrownBy(()->OrdsApiTransport.body("POST",request("t",Map.of(),List.of(),input.auth(),body,true))).hasMessage("test.jsonInvalid");
        assertThatThrownBy(()->OrdsApiTransport.body("POST",request("t",Map.of(),List.of(),input.auth(),"가".repeat(90000),true))).hasMessage("test.requestInvalid");
        for(int timeout:List.of(0,121))assertThatThrownBy(()->OrdsApiTransport.body("GET",new Request("t",Map.of(),List.of(),List.of(),"",input.auth(),timeout,false))).hasMessage("test.requestInvalid");
    }
    @Test void sessionScopeRevisionAndSingleUseAreRecheckedBeforeTransport(){try(var f=new OrdsManagementServiceTest.Fixture();var other=new OrdsManagementServiceTest.Fixture()){
        var data=catalog();data.get("MODULES").getFirst().put("STATUS","PUBLISHED");f.before=snapshot(data);other.before=f.before;
        int[] calls={0};var transport=new OrdsApiTransport(){@Override public Result execute(URI uri,String method,Request input){calls[0]++;return new Result(200,1,Map.of(),"{}",false,false,"");}};
        var service=new OrdsApiTestService(f.service,new OrdsApiTargets("{\"DB\":\"https://example.invalid/ords/\"}"),transport);
        var selection=new Selection("APP","demo","items/","GET",f.before.revision());var plan=service.prepare(f.session,selection);assertThat(calls[0]).isZero();
        assertThatThrownBy(()->service.run(other.session,empty(plan.token()))).hasMessage("test.expired");
        assertThat(service.run(f.session,empty(plan.token())).status()).isEqualTo(200);assertThat(calls[0]).isEqualTo(1);
        assertThatThrownBy(()->service.run(f.session,empty(plan.token()))).hasMessage("test.expired");
        var stale=service.prepare(f.session,selection);data.get("MODULES").getFirst().put("URI_PREFIX","changed/");f.before=snapshot(data);assertThatThrownBy(()->service.run(f.session,empty(stale.token()))).hasMessage("stale");assertThat(calls[0]).isEqualTo(1);
        assertThatThrownBy(()->service.prepare(f.session,new Selection("OTHER","demo","items/","GET",f.before.revision()))).isInstanceOf(Failure.class);
    }}
    @Test void unauthenticatedControllerNeverPreparesOrRuns(){var controller=new OrdsApiTestController(null);assertThat(controller.prepare(null,new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);assertThat(controller.run(null,new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);}
}
