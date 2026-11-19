package com.dbcompanion;

import com.dbcompanion.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SetupProfileVersionTest {
    private final String version="a".repeat(64);
    private String state(String kind,String evidence,String current){
        var saved=new SetupHistoryReview.Saved("id","parent","2026-11-19","USER_SELECTED","model",false,null,evidence);
        if(!kind.equals(saved.kind()))saved=new SetupHistoryReview.Saved("id","parent","2026-11-19",kind,"model",false,null,evidence);
        return SetupHistoryReview.withVersion(SetupHistoryReview.compare(saved,List.of(),null),saved,"APP","P",current).versionState();
    }
    private String evidence(String scheme,String owner,String profile,String hash){
        return new JsonMapper().writeValueAsString(new SetupProfileVersion(scheme,owner,profile,hash));
    }
    @Test void fingerprintComparisonIsIndependentOfOptionalSettingSnapshots(){
        String saved=evidence(SetupProfileVersion.SCHEME,"APP","P",version);
        assertThat(state("USER_SELECTED",saved,version)).isEqualTo("MATCH");
        assertThat(state("USER_SELECTED",saved,"b".repeat(64))).isEqualTo("CHANGED");
        assertThat(state("USER_SELECTED",saved,null)).isEqualTo("CURRENT_UNKNOWN");
        assertThat(state("MANUAL_ENTRY",saved,version)).isEqualTo("UNAVAILABLE");
    }
    @Test void legacyMalformedForeignAndUnsupportedSchemesAreNotMatches(){
        for(String saved:java.util.Arrays.asList(null,"{}","broken",evidence("future","APP","P",version),
                evidence(SetupProfileVersion.SCHEME,"OTHER","P",version),evidence(SetupProfileVersion.SCHEME,"APP","OTHER",version),
                evidence(SetupProfileVersion.SCHEME,"APP","P","v1")))
            assertThat(state("USER_SELECTED",saved,version)).isEqualTo("UNAVAILABLE");
    }
}
