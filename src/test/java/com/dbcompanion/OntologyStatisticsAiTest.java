package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class OntologyStatisticsAiTest {
    @Test void largerReadIsOptInAndOnlyCountsEnterPreparedAiPayload(){
        for(boolean enabled:List.of(false,true)){
            var f=new OntologyPlanSelectionTest();var calls=new AtomicInteger();
            var rows=new OntologyWizardRepository(new JdbcTemplate(f.source)){
                @Override public List<List<String>> sample(Snapshot s,List<ColumnInfo> c,int n){return List.of(List.of("1"),List.of("2"));}
                @Override public List<List<String>> profileRows(Snapshot s,List<ColumnInfo> c){calls.incrementAndGet();return Collections.nCopies(30,List.of("7.25"));}
            };
            var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","LLM"),"oci","test","v1");
            var ai=new AiAssistantRepository(new JdbcTemplate(f.source)){
                @Override public AiAssistant.Profile profile(AiAssistant.Selection s){return profile;}
            };
            var service=new OntologyWizardService(f.source,f.repository,rows,ai,null,f.json);
            try(var session=f.session()){
                session.metadata().assistant().select(profile.selection());
                var preview=service.sample(session,"APP","A",1,List.of("CODE"),10,true,enabled,Locale.ENGLISH);
                assertThat(calls.get()).isEqualTo(enabled?1:0);
                assertThat(preview.request().source()).doesNotContain("7.25");
                if(enabled)assertThat(preview.request().source()).contains("statisticsCheckedAt","NOT_WHOLE_TABLE","topFrequencies","30");
                else assertThat(preview.request().source()).doesNotContain("statisticsCheckedAt");
            }
        }
    }
}
