package com.dbcompanion;

import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.AiCreation.*;
import com.dbcompanion.repository.AiCreationRepository;
import com.dbcompanion.common.exception.MetadataEditException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiCreationTest {
    @Test void exactJsonRejectsDuplicateKeysAndPreservesDecimalReadback(){
        assertThatThrownBy(()->AiCreation.validate(input(Kind.PROFILE,"{\"provider\":\"oci\",\"provider\":\"openai\"}"),json)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(()->AiCreation.validate(input(Kind.PROFILE,"{\"provider\":\"oci\"} {}"),json)).isInstanceOf(MetadataEditException.class);
        var in=input(Kind.PROFILE,"{\"provider\":\"oci\",\"temperature\":0.1234567890123456789}");
        assertThat(AiCreation.matches(in,snapshot("{\"provider\":\"oci\",\"temperature\":\"0.1234567890123456789\"}"),json)).isTrue();
        assertThat(AiCreation.matches(in,snapshot("{\"provider\":\"oci\",\"temperature\":\"0.1234567890123456788\"}"),json)).isFalse();
        assertThat(AiCreation.attributesJson(Map.of("attributes",Map.of("temperature","0.1234567890123456789")),json)).contains("0.1234567890123456789");
    }
    @Test void sqlIdOnlyOverloadCannotBeUsedForFeedbackCopy(){
        var fields=Map.of("PROFILE_NAME","VARCHAR2","SQL_TEXT","CLOB","FEEDBACK_TYPE","VARCHAR2","RESPONSE","CLOB","FEEDBACK_CONTENT","CLOB","OPERATION","VARCHAR2");
        var args=fields.entrySet().stream().map(e->new AiCreationRepository.Argument(1,e.getKey(),e.getValue(),"IN")).toList();assertThat(AiCreationRepository.feedbackCompatible(args)).isTrue();
        var wrong=args.stream().map(a->a.name().equals("SQL_TEXT")?new AiCreationRepository.Argument(1,"SQL_ID","VARCHAR2","IN"):a).toList();assertThat(AiCreationRepository.feedbackCompatible(wrong)).isFalse();
    }
    @Test void longProfileInstructionsUseClobWithoutTheAttributeEditorLimit(){String instructions="Exact instruction. ".repeat(3000);var in=input(Kind.PROFILE,json.writeValueAsString(Map.of("provider","oci","additional_instructions",instructions)));assertThat(AiCreation.validate(in,json).get("additional_instructions").stringValue()).isEqualTo(instructions);}
    @Test void fingerprintIgnoresMapOrderingButNotValues(){
        var a=new LinkedHashMap<String,Object>();a.put("exists",true);a.put("attributes",Map.of("comments","true","provider","oci"));
        var b=new LinkedHashMap<String,Object>();b.put("attributes",new TreeMap<>((Map<String,String>)a.get("attributes")));b.put("exists",true);
        assertThat(AiCreation.fingerprint(a,json)).isEqualTo(AiCreation.fingerprint(Map.copyOf(b),json));
        b.put("exists",false);assertThat(AiCreation.fingerprint(a,json)).isNotEqualTo(AiCreation.fingerprint(b,json));
    }
    private final JsonMapper json=new JsonMapper();
    Input input(Kind kind,String attributes){return new Input("APP",kind,"","a".repeat(64),"NEW_OBJECT","Description","DISABLED",attributes,false);}
    Map<String,Object> snapshot(String attributes){return Map.of("exists",true,"profile",Map.of("PROFILE_NAME","NEW_OBJECT","PROFILE_ID","1","STATUS","DISABLED","DESCRIPTION","Description"),"attributes",json.readValue(attributes,Map.class));}
    Feedback feedback(String question,String sql){return new Feedback(question,json.writeValueAsString(Map.of("feedback_type","negative","sql_text",sql,"response","SELECT 1 FROM DUAL","feedback_content","설명")));}
    @Test void noSchemaOrOwnershipEscalation(){AiCreation.scope("APP","APP","APP");assertThatThrownBy(()->AiCreation.scope("ADMIN","APP","APP")).isInstanceOf(MetadataEditException.class);assertThatThrownBy(()->AiCreation.scope("APP","APP","OTHER")).isInstanceOf(MetadataEditException.class);}
    @Test void identifiersNeverAcceptSqlOrQualifiedNames(){for(String value:List.of("a","A.B","A;DROP","A\"","1TEST","A".repeat(126)))assertThatThrownBy(()->AiCreation.name(value)).isInstanceOf(MetadataEditException.class);assertThat(AiCreation.name("A_$#9")).isEqualTo("A_$#9");}
    @Test void textIsNeverTruncatedAndInvalidUnicodeIsRejected(){AiCreation.text("가".repeat(100),300);for(String value:List.of("가".repeat(101),"a\0","\ud800"))assertThatThrownBy(()->AiCreation.text(value,300)).isInstanceOf(MetadataEditException.class);}
    @Test void clonePreservesStringsAndConvertsOnlyKnownTypedAttributes(){var source=Map.<String,Object>of("attributes",Map.of("provider","oci","temperature","0.5","comments","true","additional_instructions","{text}\n  exact  ","object_list","[{\"owner\":\"APP\",\"name\":\"T\"}]"));var n=json.readTree(AiCreation.attributesJson(source,json));assertThat(n.get("comments").isBoolean()).isTrue();assertThat(n.get("temperature").isNumber()).isTrue();assertThat(n.get("additional_instructions").stringValue()).isEqualTo("{text}\n  exact  ");assertThat(n.get("object_list").isArray()).isTrue();}
    @Test void secretsIncludingNestedParametersAreBlockedBeforeArchiving(){for(String text:List.of("{\"provider\":\"oci\",\"api_key\":\"secret\"}","{\"provider\":\"oci\",\"extra\":{\"password\":\"secret\"}}"))assertThatThrownBy(()->AiCreation.validate(input(Kind.PROFILE,text),json)).isInstanceOf(MetadataEditException.class);}
    @Test void requiredInputsAndToolTypesAreChecked(){AiCreation.validate(input(Kind.PROFILE,"{\"provider\":\"oci\",\"comments\":true}"),json);AiCreation.validate(input(Kind.TOOL,"{\"tool_type\":\"SQL\",\"tool_params\":{\"profile_name\":\"P\"}}"),json);for(var in:List.of(input(Kind.PROFILE,"{}"),input(Kind.TEAM,"{\"agents\":[]}"),input(Kind.AGENT,"{\"role\":\"r\"}"),input(Kind.TOOL,"{\"tool_type\":\"SQL\",\"function\":\"F\"}")))assertThatThrownBy(()->AiCreation.validate(in,json)).isInstanceOf(MetadataEditException.class);}
    @Test void feedbackRejectsMissingKeySqlIdAndUnknownAttributesWithoutSkipping(){var row=feedback("Q","select ai showsql Q");AiCreation.validateFeedback(List.of(row),json);for(String attrs:List.of("{}",row.attributes().replace("negative","positive"),row.attributes().replace("\"feedback_type\"","\"unknown\""),row.attributes().replace("{","{\"sql_id\":\"old\",")))assertThatThrownBy(()->AiCreation.feedback(new Feedback("Q",attrs),json)).isInstanceOf(MetadataEditException.class);}
    @Test void duplicatesBlockWholeCopyWithoutSkipping(){
        var row=feedback("Q","SQL");
        for(var duplicate:List.of(row,feedback("different question","SQL"),feedback("Q","different SQL")))
            assertThatThrownBy(()->AiCreation.validateFeedback(List.of(row,duplicate),json)).isInstanceOf(MetadataEditException.class).hasMessage("copyDuplicate");
    }
    @Test void all119And1001DistinctEntriesFitWithoutAFixedCountLimit(){
        for(int count:List.of(119,1001)){
            var rows=IntStream.range(0,count).mapToObj(i->feedback("질문 "+i,"select ai showsql question "+i)).toList();
            assertThatCode(()->AiCreation.validateFeedback(rows,json)).doesNotThrowAnyException();
            var plan=new Plan(input(Kind.PROFILE,"{}"),Map.of("exists",false),rows);
            assertThat(plan.preview().feedback()).containsExactlyElementsOf(rows);
        }
    }
    Feedback sizedFeedback(int index,int bytes){
        var row=feedback("Q"+index,"SQL "+index);
        int padding=bytes-row.question().getBytes(StandardCharsets.UTF_8).length-row.attributes().getBytes(StandardCharsets.UTF_8).length;
        return new Feedback(row.question(),row.attributes().replace("설명","설명"+"x".repeat(padding)));
    }
    @Test void aggregateUtf8LimitAcceptsExactlyTwoMegabytesAndRejectsOneMoreByte(){
        var rows=new ArrayList<>(IntStream.range(0,10).mapToObj(i->sizedFeedback(i,AiCreation.MAX_COPY_BYTES/10)).toList());
        assertThatCode(()->AiCreation.validateFeedback(rows,json)).doesNotThrowAnyException();
        var last=rows.getLast();rows.set(9,new Feedback(last.question()+"x",last.attributes()));
        assertThatThrownBy(()->AiCreation.validateFeedback(rows,json)).isInstanceOf(MetadataEditException.class).hasMessage("copyLimit")
                .satisfies(ex->assertThat(((MetadataEditException)ex).status()).isEqualTo(413));
    }
    @Test void streamingValidationStopsAtTheSameUtf8BudgetBeforeCollectingMoreRows(){
        var batch=new FeedbackBatch();
        for(int i=0;i<9;i++)batch.add(sizedFeedback(i,200_000));
        var last=sizedFeedback(9,199_998);
        assertThatThrownBy(()->batch.add(new Feedback(last.question()+"가",last.attributes())))
                .isInstanceOf(MetadataEditException.class).hasMessage("copyLimit");
        assertThatThrownBy(()->new FeedbackBatch().add(new Feedback("","{}")))
                .isInstanceOf(MetadataEditException.class).hasMessage("copyUnsupported");
    }
    @Test void copyStillRejectsAnOversizedIndividualField(){
        var row=feedback("가".repeat(AiCreation.MAX_BYTES/3+1),"SQL");
        assertThatThrownBy(()->AiCreation.validateFeedback(List.of(row),json)).isInstanceOf(MetadataEditException.class).hasMessage("size");
        assertThatThrownBy(()->new FeedbackBatch().add(row)).isInstanceOf(MetadataEditException.class).hasMessage("size");
    }
    @Test void feedbackSelectHasNoHiddenCountCutoffAndQuotesTheTarget(){
        assertThat(AiCreationRepository.feedbackSelectSql("APP","SOURCE"))
                .contains("\"APP\".\"SOURCE_FEEDBACK_VECINDEX$VECTAB\"","ORDER BY ROWID","JSON_SERIALIZE(ATTRIBUTES RETURNING CLOB)")
                .doesNotContain("FETCH FIRST","ROWNUM","OFFSET","LIMIT");
    }
    @Test void feedbackReadbackMustMatchEveryQuestionAndValue(){var a=feedback("A","SQL A");var b=feedback("B","SQL B");assertThat(AiCreation.feedbackMatches(List.of(a,b),List.of(b,a),json)).isTrue();assertThat(AiCreation.feedbackMatches(List.of(a,b),List.of(a),json)).isFalse();assertThat(AiCreation.feedbackMatches(List.of(a,b),List.of(a,a),json)).isFalse();assertThat(AiCreation.feedbackMatches(List.of(a),List.of(feedback("A","changed")),json)).isFalse();}
    @Test void readbackChecksNameStateDescriptionAndFullDesiredAttributes(){var in=input(Kind.PROFILE,"{\"provider\":\"oci\",\"comments\":true}");assertThat(AiCreation.matches(in,snapshot("{\"provider\":\"oci\",\"comments\":\"true\"}"),json)).isTrue();assertThat(AiCreation.matches(in,snapshot("{\"provider\":\"oci\",\"comments\":\"false\"}"),json)).isFalse();assertThat(AiCreation.matches(in,Map.of("exists",false),json)).isFalse();}
    @Test void plansAreIsolatedAndClearedOnNewPreview(){var state=new State();var a=new Plan(input(Kind.PROFILE,"{}"),Map.of("exists",false),List.of());state.put(a);assertThat(state.get(a.token)).isSameAs(a);assertThatThrownBy(()->state.get("forged")).isInstanceOf(MetadataEditException.class);var b=new Plan(a.input,a.original,List.of());state.put(b);assertThatThrownBy(()->state.get(a.token)).isInstanceOf(MetadataEditException.class);b.blocked=true;assertThatThrownBy(()->state.get(b.token)).isInstanceOf(MetadataEditException.class);state.clear();assertThatThrownBy(()->state.get(b.token)).isInstanceOf(MetadataEditException.class);}
    @Test void onlyVerifiedOracleSignaturesAndBoundStatementsAreUsed(){for(var kind:Kind.values()){
        var args=List.of(new AiCreationRepository.Argument(1,kind+"_NAME","VARCHAR2","IN"),new AiCreationRepository.Argument(1,"ATTRIBUTES","CLOB","IN"),new AiCreationRepository.Argument(1,"STATUS","VARCHAR2","IN"),new AiCreationRepository.Argument(1,"DESCRIPTION","CLOB","IN"));assertThat(AiCreationRepository.compatible(kind,args)).isTrue();assertThat(AiCreationRepository.compatible(kind,args.subList(0,3))).isFalse();String sql=AiCreationRepository.createSql("SYS",kind);assertThat(sql).contains("CREATE_"+kind,"attributes => a","status => ?").doesNotContain("EXECUTE IMMEDIATE","DROP","REPLACE");assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(4);
    }assertThat(AiCreationRepository.feedbackSql("SYS")).contains("sql_text => q","'negative'","operation => 'add'").doesNotContain("GENERATE","runsql","INSERT");}
}
