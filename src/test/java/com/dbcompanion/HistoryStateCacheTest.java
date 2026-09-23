package com.dbcompanion;

import com.dbcompanion.model.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryStateCacheTest {
    private DatabaseSession session(){return new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));}
    private MetadataHistory.State state(String message){return new MetadataHistory.State(true,false,true,message,"B","A","ADMIN",false,"ADMINISTER DATABASE TRIGGER");}
    @Test void tabAndBrowserReloadReuseDeniedStatusUntilExplicitRefresh() {
        var session=session();var calls=new AtomicInteger();
        java.util.function.Supplier<MetadataHistory.State> loader=()->state("read "+calls.incrementAndGet());
        var first=session.historyState("APP","T",false,loader);
        assertThat(session.historyState("APP","T",false,loader)).isSameAs(first);
        assertThat(session.historyState("APP","T",false,loader)).isSameAs(first);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(session.historyState("APP","T",true,loader).message()).isEqualTo("read 2");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void schemasTablesAndLoginSessionsDoNotShareState() {
        var a=session();var b=session();var calls=new AtomicInteger();
        java.util.function.Supplier<MetadataHistory.State> loader=()->state("read "+calls.incrementAndGet());
        a.historyState("APP","T",false,loader);a.historyState("OTHER","T",false,loader);a.historyState("APP","U",false,loader);b.historyState("APP","T",false,loader);
        a.selectSchema("OTHER");a.selectSchema("APP");a.historyState("APP","T",false,loader);
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void mutationsPublishResultOrMarkUnknownWithoutPretendingToReload() {
        var session=session();var updated=state("updated");
        session.rememberHistoryState("APP","T",updated);
        assertThat(session.historyState("APP","T",false,()->{throw new AssertionError("Unexpected DB query");})).isSameAs(updated);
        session.uncertainHistoryState("APP","T");
        var uncertain=session.historyState("APP","T",false,()->{throw new AssertionError("Unexpected DB query");});
        assertThat(uncertain.healthy()).isFalse();assertThat(uncertain.canManage()).isFalse();assertThat(uncertain.message()).contains("상태를 갱신");
    }
    @Test void failedRefreshDoesNotResurrectPreviousHealthyStatus() {
        var session=session();session.rememberHistoryState("APP","T",state("old"));
        assertThatThrownBy(()->session.historyState("APP","T",true,()->{throw new IllegalStateException("Unavailable");})).hasMessage("Unavailable");
        assertThat(session.historyState("APP","T",false,()->{throw new AssertionError("Unexpected retry");}).healthy()).isFalse();
    }
}
