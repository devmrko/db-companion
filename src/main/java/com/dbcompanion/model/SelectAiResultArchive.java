package com.dbcompanion.model;

import com.dbcompanion.service.OntologyInquiry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An explicit, immutable copy of fetched rows; never reconstructed by rerunning SQL. */
public final class SelectAiResultArchive {
    private SelectAiResultArchive() {}
    public static final String TYPE="SELECT_AI_RESULT";
    public record Snapshot(int version,String id,String resultId,String schema,String profile,String question,
                           String conversationId,Instant savedAt,long elapsedMillis,OntologyInquiry.Rows data) {}
    public record Item(String id,String profile,String question,String executedAt,int rowCount,boolean truncated) {}
    public record Page(List<Item> items,int number,boolean hasNext) {}
    public static Snapshot capture(String schema,SelectAiTest.Outcome outcome,SelectAiTest.ExecutionResult execution) {
        if(outcome==null||execution==null||outcome.action()!=SelectAiTest.Action.SQL||outcome.error()!=null
                ||execution.error()!=null||execution.data()==null||!outcome.id().equals(execution.resultId())
                ||!schema.equals(outcome.profile().selection().owner())||!schema.equals(execution.data().actor()))
            throw new AiAssistant.Failure(409,"resultArchive.stale","현재 실행한 조회 결과가 없습니다. 결과를 확인한 뒤 보관하세요.");
        var data=execution.data();
        String id=UUID.nameUUIDFromBytes((outcome.id()+"\n"+data.executedAt()+"\n"+data.hash()).getBytes(StandardCharsets.UTF_8)).toString();
        return new Snapshot(1,id,outcome.id(),schema,outcome.profile().selection().name(),outcome.question(),
                outcome.memory()==null?null:outcome.memory().conversationId(),Instant.now(),execution.elapsedMillis(),data);
    }
}
