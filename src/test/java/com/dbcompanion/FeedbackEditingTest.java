package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.FeedbackEditing.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FeedbackEditingTest {
    final JsonMapper json=new JsonMapper();
    Save save(String sql,String response,boolean confirmed,boolean consent){return new Save("t",sql,response,"설명",confirmed,consent);}
    @Test void newFeedbackHasExactSourceAndAllNullableKeys(){String sql="select ai showsql 사용자별 권역은?";var desired=FeedbackEditing.desired(save(sql,"SELECT 1 FROM DUAL",true,true),json);assertThat(desired.question()).isEqualTo("사용자별 권역은?");var attributes=json.readTree(desired.attributes());assertThat(attributes.get("sql_text").stringValue()).isEqualTo(sql);assertThat(attributes.get("sql_id").isNull()).isTrue();assertThat(AiCreation.feedback(desired,json).get("response").stringValue()).isEqualTo("SELECT 1 FROM DUAL");}
    @Test void bothConsentAndConfirmationAreRequired(){for(var value:List.of(save("select ai showsql Q","SQL",false,true),save("select ai showsql Q","SQL",true,false)))assertThatThrownBy(()->FeedbackEditing.desired(value,json)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);}
    @Test void onlyExplicitShowsqlPromptAndNonemptyResponseAreAccepted(){for(String value:List.of("Q","select ai runsql Q","select * from T","select ai showsql "))assertThatThrownBy(()->FeedbackEditing.desired(save(value,"SQL",true,true),json)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);assertThatThrownBy(()->FeedbackEditing.desired(save("select ai showsql Q"," ",true,true),json)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);}
    @Test void longUnicodeValuesAreNotCutOrNormalized(){String text="한글😀\n".repeat(4000)+"END  ";var row=FeedbackEditing.desired(save("select ai showsql Q",text,true,true),json);assertThat(json.readTree(row.attributes()).get("response").stringValue()).isEqualTo(text);}
    @Test void tokenIsSessionLocalSingleUseAndClearable(){var first=new State();var second=new State();var p=new Plan("APP","P","",Map.of("exists",true),null);first.set(p);assertThatThrownBy(()->second.take(p.token)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);assertThatThrownBy(()->first.take("forged")).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);assertThat(first.take(p.token)).isSameAs(p);assertThatThrownBy(()->first.take(p.token)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);first.clear();assertThatThrownBy(()->first.take(p.token)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);}
    @Test void noImplicitOverwriteOrSqlExecutionIsPresent() throws Exception {
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/service/FeedbackEditingService.java"));
        assertThat(service).contains("FEEDBACK_ADD","FEEDBACK_REPLACE","unchanged(p,request.sqlText())","history.before","history.after","p.before==null").doesNotContain("GENERATE(","runsql","EXECUTE IMMEDIATE","DROP ","DELETE FROM");
        assertThat(service.indexOf("history.before")).isLessThan(service.indexOf("repository.addFeedback"));
    }
}
