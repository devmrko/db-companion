package com.dbcompanion;

import com.dbcompanion.model.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SetupHistoryReviewTest {
    private final JsonMapper json=new JsonMapper();
    private final Instant time=Instant.parse("2026-11-19T00:00:00Z");
    private SetupHistoryReview.Saved saved(String kind,String availability,Instant checked,String values){
        return new SetupHistoryReview.Saved("id","parent",time.toString(),kind,"model",true,
                json.writeValueAsString(new ProblemQuestion.Snapshot(values,availability,checked)));
    }
    @Test void comparesOnlyRecordedFieldsWithoutReturningValuesOrClaimingSuccess(){
        var value=saved("USER_SELECTED","PRE_GENERATION_READ",time.minusSeconds(5),"{\"comments\":\"true\",\"private\":\"excluded\"}");
        var review=SetupHistoryReview.compare(value,List.of(new AiProfileAttribute("comments","false"),new AiProfileAttribute("model","model")),null);
        assertThat(review.comparison()).isEqualTo("DIFFERENT");
        assertThat(review.compared()).containsExactly("comments","model");
        assertThat(review.changed()).containsExactly("comments");
        assertThat(review.errorRecorded()).isTrue();
        assertThat(json.writeValueAsString(review)).doesNotContain("excluded","private","SUCCESS");
    }
    @Test void manualMissingMalformedRedactedAndPostExecutionSnapshotsCannotBecomeMatches(){
        var attrs=List.of(new AiProfileAttribute("comments","true"));
        assertThat(SetupHistoryReview.compare(saved("MANUAL_ENTRY","CAPTURED",time,"{}"),attrs,null).comparison()).isEqualTo("MANUAL");
        assertThat(SetupHistoryReview.compare(saved("USER_SELECTED","CAPTURED",time.plusSeconds(1),"{}"),attrs,null).comparison()).isEqualTo("AFTER");
        assertThat(SetupHistoryReview.compare(saved("USER_SELECTED","POST_GENERATION_READ",time,"{}"),attrs,null).comparison()).isEqualTo("AFTER");
        for(String values:List.of("broken","[]","{\"comments\":null}","{\"comments\":\"[REDACTED]\"}"))
            assertThat(SetupHistoryReview.compare(saved("USER_SELECTED","CAPTURED",time,values),attrs,null).comparison()).isEqualTo("UNAVAILABLE");
        assertThat(SetupHistoryReview.compare(saved("USER_SELECTED","NOT_REQUESTED",time,"{}"),attrs,null).comparison()).isEqualTo("UNAVAILABLE");
        assertThat(SetupHistoryReview.compare(saved("USER_SELECTED","CAPTURED",null,"{}"),attrs,null).comparison()).isEqualTo("UNAVAILABLE");
    }
    @Test void equivalentJsonAndLinkedRecoveryDoNotProveResolved(){
        var note=new SetupHistoryReview.Recovery("note","retest");
        var review=SetupHistoryReview.compare(saved("USER_SELECTED","CAPTURED",time,"{\"object_list\":\"[ { \\\"owner\\\": \\\"APP\\\", \\\"name\\\": \\\"T\\\" } ]\"}"),
                List.of(new AiProfileAttribute("object_list","[{\"name\":\"T\",\"owner\":\"APP\"}]"),new AiProfileAttribute("model","model")),note);
        assertThat(review.comparison()).isEqualTo("SAME_FIELDS");
        assertThat(review.recovery()).isEqualTo(note);
        assertThat(review.errorRecorded()).isTrue();
    }
    @Test void removedSettingsAreDifferencesNotSuccessfulRecovery(){
        var result=SetupHistoryReview.compare(saved("USER_SELECTED","CAPTURED",time,"{\"comments\":\"true\"}"),List.of(),null);
        assertThat(result.changed()).containsExactly("comments","model");
    }
}
