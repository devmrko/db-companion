package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.model.ProfileHistory;
import com.dbcompanion.repository.AiExecutionHistoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import static com.dbcompanion.service.AiSetupService.State;

/** Metadata-only readiness; never installs, collects, enables or samples history payloads. */
@Service
public class AiSetupHistoryService {
    public record Check(String key,State state,String link,String detail) {}
    private final ProblemQuestionService problems;
    private final ProfileHistoryService profiles;
    private final AuditHistoryService audit;
    private final AiExecutionHistoryRepository executions;
    private final SessionDataSource source;
    public AiSetupHistoryService(ProblemQuestionService problems,ProfileHistoryService profiles,
            AuditHistoryService audit,AiExecutionHistoryRepository executions,SessionDataSource source) {
        this.problems=problems;this.profiles=profiles;this.audit=audit;this.executions=executions;this.source=source;
    }
    public static State storage(String value) {
        return switch(value==null?"":value) {
            case "READY" -> State.CONFIGURED;
            case "MISSING","MISMATCH","CONFLICT" -> State.ACTION;
            default -> State.UNKNOWN;
        };
    }
    public static State enabled(Boolean value) {return value==null?State.UNKNOWN:value?State.CONFIGURED:State.ACTION;}
    public List<Check> diagnose(PoolSession session,List<AiProfileAttribute> attributes) {
        String owner=session.metadata().info().username();
        if(!owner.equals(session.metadata().selectedSchema()))throw new IllegalArgumentException("History diagnosis requires owner schema");
        var checks=new ArrayList<Check>();
        State records;
        try{records=storage(problems.status(session));}catch(RuntimeException ex){records=State.UNKNOWN;}
        checks.add(new Check("records",records,"/ai-test/problems","DBC_APP_RECORD"));
        String conversation=attributes.stream().filter(a->"conversation".equals(a.name())).map(a->a.value()==null?"":a.value()).findFirst().orElse("");
        checks.add(new Check("conversation",enabled("true".equalsIgnoreCase(conversation)),"/ai-profiles","conversation="+(conversation.isBlank()?"unset":conversation)));
        State view;
        source.bind(session.pool(),owner);
        try{view=enabled(executions.readable());}catch(RuntimeException ex){view=State.UNKNOWN;}finally{source.clear();}
        checks.add(new Check("conversationView",view,"/ai-executions",AiExecutionHistoryRepository.VIEW));
        try {
            var state=profiles.state(session,new ProfileHistory.Target(owner,null));
            checks.add(new Check("profileStore",enabled(state.installed()),"/ai-profiles",state.policy()));
            checks.add(new Check("profileAudit",enabled(state.enabled()),"/ai-profiles",state.policy()));
            checks.add(new Check("profileCollect",state.enabled()==null?State.UNKNOWN:enabled(state.canCollect()),"/ai-profiles",""));
        }catch(RuntimeException ex){for(String key:List.of("profileStore","profileAudit","profileCollect"))checks.add(new Check(key,State.UNKNOWN,"/ai-profiles",""));}
        try {
            var state=audit.status(session);
            checks.add(new Check("auditSource",storage(state.sourceState()),"/db/audit",state.source()));
            checks.add(new Check("auditStore",storage(state.store()),"/db/audit",""));
            checks.add(new Check("auditCollector",storage(state.packageState()),"/db/audit",""));
            checks.add(new Check("auditSchedule",state.ready()?enabled(state.enabled()):storage(state.jobState()).equals(State.UNKNOWN)?State.UNKNOWN:State.ACTION,"/db/audit",
                    "interval_minutes="+state.config().getOrDefault("INTERVAL_MINUTES","—")+" · retention_days="+state.config().getOrDefault("RETENTION_DAYS","—")));
        }catch(RuntimeException ex){for(String key:List.of("auditSource","auditStore","auditCollector","auditSchedule"))checks.add(new Check(key,State.UNKNOWN,"/db/audit",""));}
        checks.add(new Check("objectHistory",State.UNKNOWN,"/tables",""));
        return AiSetupCatalog.history(checks);
    }
}
