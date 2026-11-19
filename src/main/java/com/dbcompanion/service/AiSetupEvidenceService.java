package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.SetupEvidence;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AiSetupEvidenceService {
    private final AiExecutionHistoryService conversations;
    private final ProblemQuestionService problems;
    public AiSetupEvidenceService(AiExecutionHistoryService conversations, ProblemQuestionService problems) {
        this.conversations = conversations; this.problems = problems;
    }
    public List<SetupEvidence.Group> read(PoolSession session, String profile) {
        if (!session.metadata().info().username().equals(session.metadata().selectedSchema()))
            throw new IllegalArgumentException("Saved evidence requires owner schema");
        if (profile == null || profile.isBlank() || profile.length() > 128) throw new IllegalArgumentException("Invalid profile");
        return List.of(group("conversation", () -> conversations.recentMetadata(session, profile)),
                group("problem", () -> problems.recentMetadata(session, profile)));
    }
    private SetupEvidence.Group group(String name, Supplier<List<SetupEvidence.Item>> read) {
        try {
            var items = read.get();
            return new SetupEvidence.Group(name, items.isEmpty() ? "EMPTY" : "RECORDED", items);
        } catch (RuntimeException ex) {
            return new SetupEvidence.Group(name, "UNKNOWN", List.of());
        }
    }
    public com.dbcompanion.model.SetupHistoryReview.Result review(PoolSession session,String profile,
            List<com.dbcompanion.model.AiProfileAttribute> attributes){
        if(!session.metadata().info().username().equals(session.metadata().selectedSchema()))
            throw new IllegalArgumentException("Saved evidence requires owner schema");
        try {
            var rows=problems.setupHistory(session,profile,attributes);
            return new com.dbcompanion.model.SetupHistoryReview.Result(rows.isEmpty()?"EMPTY":"RECORDED",rows);
        }catch(RuntimeException ex){return new com.dbcompanion.model.SetupHistoryReview.Result("UNKNOWN",List.of());}
    }
}
