package com.dbcompanion.service;

import java.io.IOException;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Version-controlled prerequisite/action catalogue shared by screen diagnosis and assistant context. */
public final class AiSetupCatalog {
    private AiSetupCatalog() {}
    public record Goal(String id,List<String> requires,List<String> actions) {}
    public record Requirement(String id,String scope,String actor,String link,String condition,String probe,String level,String technicalTerm) {}
    public record Action(String id,String actor,String attribute,String value,List<String> requires,String preview,String apply,String verification,boolean confirmationRequired) {}
    public record Spec(int version,List<Goal> goals,List<Requirement> requirements,List<Action> actions,List<String> evidenceRules) {}
    private static final Spec SPEC=load();
    private static Spec load() {
        try(var input=AiSetupCatalog.class.getResourceAsStream("/setup/capabilities.json")) {
            if(input==null)throw new IllegalStateException("Setup catalogue missing");
            return new JsonMapper().readValue(input,Spec.class);
        }catch(IOException ex){throw new IllegalStateException("Setup catalogue unavailable",ex);}
    }
    public static Spec spec(){return SPEC;}
    public static List<String> purposes(){return SPEC.goals().stream().map(Goal::id).toList();}
    public static Goal goal(String id){return SPEC.goals().stream().filter(g->g.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown setup purpose"));}
    public static Requirement requirement(String scope,String id){return SPEC.requirements().stream().filter(r->r.scope().equals(scope)&&r.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown setup requirement"));}
    public static Action action(String id){return SPEC.actions().stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown setup action"));}
    public static boolean eligible(String action,List<AiSetupService.Check> checks,List<AiSetupHistoryService.Check> histories) {
        return action(action).requires().stream().allMatch(id->{
            String[] parts=id.split("\\.",2);
            return parts.length==2 && (parts[0].equals("configuration")
                ? checks.stream().anyMatch(c->c.key().equals(parts[1])&&c.state()==AiSetupService.State.CONFIGURED)
                : parts[0].equals("history")&&histories.stream().anyMatch(c->c.key().equals(parts[1])&&c.state()==AiSetupService.State.CONFIGURED));
        });
    }
    public static List<AiSetupService.Check> configuration(String purpose,List<AiSetupService.Check> observed) {
        return goal(purpose).requires().stream().map(id->{
            var definition=requirement("configuration",id);
            var state=observed.stream().filter(c->c.key().equals(id)).map(AiSetupService.Check::state).findFirst().orElse(AiSetupService.State.UNKNOWN);
            return new AiSetupService.Check(id,state,definition.link());
        }).toList();
    }
    public static List<AiSetupHistoryService.Check> history(List<AiSetupHistoryService.Check> observed) {
        return SPEC.requirements().stream().filter(r->r.scope().equals("history")).map(definition->{
            var check=observed.stream().filter(c->c.key().equals(definition.id())).findFirst().orElse(null);
            return new AiSetupHistoryService.Check(definition.id(),check==null?AiSetupService.State.UNKNOWN:check.state(),definition.link(),check==null?"":check.detail());
        }).toList();
    }
}
