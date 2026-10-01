package com.dbcompanion;

import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.model.OrdsManagement.*;
import com.dbcompanion.service.OrdsManagementService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrdsManagementTest {
    static final Set<String> APIS=Set.of("ENABLE_SCHEMA","DROP_REST_FOR_SCHEMA","DEFINE_MODULE","RENAME_MODULE","PUBLISH_MODULE","DELETE_MODULE","DEFINE_TEMPLATE","DELETE_TEMPLATE","DEFINE_HANDLER","DELETE_HANDLER","DEFINE_PARAMETER");
    static Map<String,String> row(String... pairs){var row=new TreeMap<String,String>();for(int i=0;i<pairs.length;i+=2)row.put(pairs[i],pairs[i+1]);return row;}
    static Map<String,List<Map<String,String>>> catalog(){
        var data=new TreeMap<String,List<Map<String,String>>>();
        data.put("SCHEMAS",new ArrayList<>(List.of(row("ID","1","PARSING_SCHEMA","APP","TYPE","BASE_PATH","PATTERN","app","STATUS","ENABLED","AUTO_REST_AUTH","ENABLED","OPS_ALLOWED","0","PRE_HOOK",null))));
        data.put("MODULES",new ArrayList<>(List.of(row("ID","2","NAME","demo","URI_PREFIX","/demo/","ITEMS_PER_PAGE","25","STATUS","NOT_PUBLISHED","COMMENTS","comment","ORIGINS_ALLOWED",null,"PRE_HOOK",null))));
        data.put("TEMPLATES",new ArrayList<>(List.of(row("ID","3","MODULE_ID","2","URI_TEMPLATE","items/","PRIORITY","0","ETAG_TYPE","HASH","ETAG_QUERY",null,"COMMENTS",null))));
        data.put("HANDLERS",new ArrayList<>(List.of(row("ID","4","TEMPLATE_ID","3","METHOD","GET","SOURCE_TYPE","json/collection","SOURCE","select 1 from dual","ITEMS_PER_PAGE",null,"MIMES_ALLOWED",null,"COMMENTS",null,"MLE_ENV_NAME",null))));
        data.put("PARAMETERS",new ArrayList<>(List.of(row("ID","5","HANDLER_ID","4","NAME","X-Value","BIND_VARIABLE_NAME","value","SOURCE_TYPE","HEADER","ACCESS_METHOD","IN","PARAM_TYPE","STRING","COMMENTS",null))));
        return data;
    }
    static Snapshot snapshot(Map<String,List<Map<String,String>>> data){return new Snapshot("AVAILABLE","",APIS,data,OrdsManagement.digest(data));}
    static Map<String,String> values(Kind kind){return switch(kind){
        case SCHEMA->Map.of("enabled","false","mappingType","BASE_PATH","mappingPattern","app","autoRestAuth","true");
        case MODULE->Map.of("basePath","/changed/","itemsPerPage","25","status","NOT_PUBLISHED","comments","comment");
        case TEMPLATE->Map.of("priority","0","etagType","HASH","etagQuery","","comments","");
        case HANDLER->Map.of("sourceType","json/collection","source","select :value from dual","itemsPerPage","","mimesAllowed","","comments","");};}
    static Input input(Snapshot snapshot,Kind kind,Action action,Map<String,String> values){return new Input("APP",kind,action,"demo","items/","GET",snapshot.revision(),values);}
    @Test void allHierarchyDeletesUseDocumentedNamedSignaturesAndExactImpact(){
        var snapshot=snapshot(catalog());
        for(Kind kind:Kind.values()){
            var plan=OrdsManagementService.plan(input(snapshot,kind,Action.DELETE,Map.of()),snapshot);
            assertThat(plan.destructive()).isTrue();assertThat(plan.affected().get("parameters")).isEqualTo(1);
            assertThat(plan.statements().getFirst().sql()).contains("ORDS_METADATA.ORDS.").doesNotContain("DROP USER","DROP TABLE");
            if(kind==Kind.TEMPLATE||kind==Kind.HANDLER)assertThat(plan.statements().getFirst().sql()).contains("p_uri_template => ?");
        }
    }
    @Test void createAndDeleteAreNotSilentUpsertsOrSuccessfulNoops(){
        var data=catalog();var snap=snapshot(data);
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.MODULE,Action.CREATE,values(Kind.MODULE)),snap)).hasMessage("exists");
        data.get("HANDLERS").clear();var empty=snapshot(data);
        assertThatThrownBy(()->OrdsManagementService.plan(input(empty,Kind.HANDLER,Action.DELETE,Map.of()),empty)).hasMessage("missing");
    }
    @Test void unavailableAndVersionSpecificApisBlockPreview(){
        var snap=snapshot(catalog());var missing=new Snapshot("AVAILABLE","",Set.of("DEFINE_HANDLER"),snap.tables(),snap.revision());
        assertThatThrownBy(()->OrdsManagementService.plan(input(missing,Kind.HANDLER,Action.DELETE,Map.of()),missing)).hasMessage("UNSUPPORTED");
        for(String status:List.of("ACCESS_REQUIRED","METADATA_UNAVAILABLE","NOT_DETECTED","LIMIT")){
            var unavailable=new Snapshot(status,"",Set.of(),Map.of(),snap.revision());
            assertThatThrownBy(()->OrdsManagementService.plan(input(unavailable,Kind.MODULE,Action.DELETE,Map.of()),unavailable)).hasMessage(status);
        }
    }
    @Test void populatedOrPolicyBearingModulesUseNonDestructiveApis(){
        var data=catalog();var snap=snapshot(data);
        assertThat(OrdsManagementService.plan(input(snap,Kind.MODULE,Action.UPDATE,values(Kind.MODULE)),snap).statements()).extracting(Statement::api).containsExactly("RENAME_MODULE","PUBLISH_MODULE");
        var changed=new HashMap<>(values(Kind.MODULE));changed.put("comments","changed");
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.MODULE,Action.UPDATE,changed),snap)).hasMessage("moduleChildren");
        data.get("TEMPLATES").clear();data.get("MODULES").getFirst().put("PRE_HOOK","app.check_auth");var policy=snapshot(data);
        assertThat(OrdsManagementService.plan(input(policy,Kind.MODULE,Action.UPDATE,values(Kind.MODULE)),policy).statements()).extracting(Statement::api).doesNotContain("DEFINE_MODULE");
    }
    @Test void templateRedefinitionCannotDeleteChildrenAndMleIsNotSilentlyConverted(){
        var data=catalog();var snap=snapshot(data);
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.TEMPLATE,Action.UPDATE,values(Kind.TEMPLATE)),snap)).hasMessage("templateChildren");
        data.get("HANDLERS").getFirst().put("MLE_ENV_NAME","PRIVATE_ENV");var mle=snapshot(data);
        assertThatThrownBy(()->OrdsManagementService.plan(input(mle,Kind.HANDLER,Action.UPDATE,values(Kind.HANDLER)),mle)).hasMessage("UNSUPPORTED");
    }
    @Test void handlerSourceIsOnlyBoundAndEveryExistingParameterIsPreserved(){
        var snap=snapshot(catalog());var values=new HashMap<>(values(Kind.HANDLER));String hostile="BEGIN EXECUTE IMMEDIATE 'drop table example'; END; -- </script>";values.put("source",hostile);
        var plan=OrdsManagementService.plan(input(snap,Kind.HANDLER,Action.UPDATE,values),snap);
        assertThat(plan.statements()).extracting(Statement::api).containsExactly("DEFINE_HANDLER","DEFINE_PARAMETER");
        assertThat(plan.statements().getFirst().sql()).doesNotContain(hostile,"EXECUTE IMMEDIATE");assertThat(plan.statements().getFirst().bindings()).contains(hostile);
        assertThat(plan.statements().getLast().bindings()).contains("X-Value","value","HEADER","STRING","IN");
    }
    @Test void methodSourceCompatibilityAndUnknownFieldsAreValidatedBeforeExecution(){
        var snap=snapshot(catalog());var bad=new HashMap<>(values(Kind.HANDLER));bad.put("sourceType","plsql/block");
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.HANDLER,Action.UPDATE,bad),snap)).isInstanceOf(Failure.class);
        bad.put("sourceType","json/collection");bad.put("unknownPolicy","replace");
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.HANDLER,Action.UPDATE,bad),snap)).hasMessage("invalid");
    }
    @Test void readbackRejectsLostParameterUnexpectedPolicyChangesAndSuccessfulNoop(){
        var before=snapshot(catalog());var in=input(before,Kind.HANDLER,Action.UPDATE,values(Kind.HANDLER));
        assertThatThrownBy(()->OrdsManagementService.verify(in,before,before)).hasMessage("verifyFailed");
        var changed=catalog();changed.get("HANDLERS").getFirst().put("SOURCE",values(Kind.HANDLER).get("source"));
        assertThatCode(()->OrdsManagementService.verify(in,before,snapshot(changed))).doesNotThrowAnyException();
        changed.get("PARAMETERS").clear();assertThatThrownBy(()->OrdsManagementService.verify(in,before,snapshot(changed))).hasMessage("verifyFailed");
        var policy=catalog();policy.get("HANDLERS").getFirst().put("SOURCE",values(Kind.HANDLER).get("source"));policy.get("MODULES").getFirst().put("PRE_HOOK","changed");
        assertThatThrownBy(()->OrdsManagementService.verify(in,before,snapshot(policy))).hasMessage("verifyFailed");
    }
    @Test void revisionsIncludeUnknownPolicyFieldsAndUnicodeIsNeverTruncated(){
        var data=catalog();String before=snapshot(data).revision();data.get("MODULES").getFirst().put("PRE_HOOK","new");assertThat(snapshot(data).revision()).isNotEqualTo(before);
        for(String text:List.of("x\0", "\ud800", "가".repeat(70_000)))assertThatThrownBy(()->OrdsManagement.field(Map.of("x",text),"x",true,200_000)).isInstanceOf(Failure.class);
    }
    @Test void pathSlashNormalizationAndPriorityRangeAreExplicit(){
        var before=snapshot(catalog());var changed=catalog();changed.get("MODULES").getFirst().put("URI_PREFIX","changed/");
        assertThatCode(()->OrdsManagementService.verify(input(before,Kind.MODULE,Action.UPDATE,values(Kind.MODULE)),before,snapshot(changed))).doesNotThrowAnyException();
        var badPath=new HashMap<>(values(Kind.MODULE));badPath.put("basePath","missing-trailing-slash");
        assertThatThrownBy(()->OrdsManagementService.plan(input(before,Kind.MODULE,Action.UPDATE,badPath),before)).hasMessage("invalid");
        var data=catalog();data.get("HANDLERS").clear();data.get("PARAMETERS").clear();var snap=snapshot(data);var badPriority=new HashMap<>(values(Kind.TEMPLATE));badPriority.put("priority","10");
        assertThatThrownBy(()->OrdsManagementService.plan(input(snap,Kind.TEMPLATE,Action.UPDATE,badPriority),snap)).hasMessage("invalid");
    }
}
