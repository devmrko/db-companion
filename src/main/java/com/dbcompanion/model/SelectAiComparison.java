package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;

/** Session-only A/B state. Every side has independent one-use tokens and observations. */
public final class SelectAiComparison {
    private static final int MAX_SAVE_LEDGER=64;
    private SelectAiComparison() {}
    public record Preview(String token,AiAssistant.Profile profile,String source,Instant expires) {}
    public record Plan(String generation,Preview left,Preview right,String question,int baseCalls,int showPromptCalls,int aiComparisonCalls) {}
    public record ShowPromptPlan(String generation,Preview left,Preview right,int additionalCalls) {}
    public record InspectionRequest(String generation,String side,AiAssistant.Profile profile,String question,SelectAiInspection.Snapshot snapshot) {}
    public record Result(SelectAiTest.Outcome left,SelectAiTest.Outcome right,SelectAiInspection.Snapshot leftInspection,SelectAiInspection.Snapshot rightInspection,SelectAiTest.Outcome leftPrompt,SelectAiTest.Outcome rightPrompt) {}
    public record AiPreview(String generation,String token,AiAssistant.Profile profile,String source,Instant expires) {}
    public record SavePreview(String token,String generation,String side,String resultId,Instant expires) {}
    public record SaveSelection(SelectAiTest.Outcome outcome,SelectAiInspection.Snapshot inspection,SelectAiTest.Outcome prompt) {}
    public record SaveBegin(SaveSelection selection,String savedId) {}
    public static final class State {
        private Plan plan; private SelectAiTest.Outcome left,right,leftPrompt,rightPrompt;
        private SelectAiInspection.Snapshot leftInspection,rightInspection;
        private boolean leftRunning,rightRunning,leftInspectionRunning,rightInspectionRunning,leftPromptRunning,rightPromptRunning,aiRunning;
        private ShowPromptPlan showPrompt; private AiPreview ai; private String aiResult;
        private final Map<String,SaveSlot> saves=new LinkedHashMap<>();
        private record SaveSlot(SavePreview preview,SaveSelection selection,String purpose,String savedId,boolean saving,boolean unconfirmed) {}
        private String saveKey(String generation,String side,String resultId){return generation+"\u0000"+side+"\u0000"+resultId;}
        public synchronized Plan prepare(AiAssistant.Profile a,AiAssistant.Profile b,String question,String language,Instant now){
            SelectAiTest.question(question);busy();if(a==null||b==null||a.selection().equals(b.selection()))throw new AiAssistant.Failure(400,"testComparison.profiles","서로 다른 두 프로필을 선택해 주세요.");
            String generation=UUID.randomUUID().toString();plan=new Plan(generation,new Preview(UUID.randomUUID().toString(),a,SelectAiTest.prompt(SelectAiTest.Action.SQL,question,language),now.plusSeconds(600)),new Preview(UUID.randomUUID().toString(),b,SelectAiTest.prompt(SelectAiTest.Action.SQL,question,language),now.plusSeconds(600)),question,2,0,0);
            left=null;right=null;leftPrompt=null;rightPrompt=null;leftInspection=null;rightInspection=null;showPrompt=null;ai=null;aiResult=null;return plan;
        }
        private void busy(){if(leftRunning||rightRunning||leftInspectionRunning||rightInspectionRunning||leftPromptRunning||rightPromptRunning||aiRunning||saves.values().stream().anyMatch(SaveSlot::saving))throw new AiAssistant.Failure(409,"testComparison.busy","비교 요청 처리 중입니다.");}
        public synchronized Preview consume(String token,boolean consent,Instant now){
            if(!consent)throw new AiAssistant.Failure(400,"aitest.consentRequired","외부 전송·사용량 확인이 필요합니다.");if(plan==null||!now.isBefore(plan.left().expires()))throw AiAssistant.stale();
            Preview value=plan.left().token().equals(token)?plan.left():plan.right().token().equals(token)?plan.right():null;if(value==null)throw AiAssistant.stale();
            if(value==plan.left()){if(leftRunning||left!=null)throw AiAssistant.stale();leftRunning=true;}else{if(rightRunning||right!=null)throw AiAssistant.stale();rightRunning=true;}return value;
        }
        public synchronized void finish(String token,SelectAiTest.Outcome value){if(plan==null)throw AiAssistant.stale();if(plan.left().token().equals(token)&&leftRunning){left=value;leftRunning=false;}else if(plan.right().token().equals(token)&&rightRunning){right=value;rightRunning=false;}else throw AiAssistant.stale();}
        public synchronized InspectionRequest beginInspection(String side,String id){
            if(plan==null||!("left".equals(side)||"right".equals(side)))throw AiAssistant.stale();boolean leftSide="left".equals(side);var snapshot=leftSide?leftInspection:rightInspection;
            if((leftSide?leftInspectionRunning:rightInspectionRunning)||id!=null&&(snapshot==null||!id.equals(snapshot.id())))throw AiAssistant.stale();if(leftSide)leftInspectionRunning=true;else rightInspectionRunning=true;
            return new InspectionRequest(plan.generation(),side,leftSide?plan.left().profile():plan.right().profile(),plan.question(),snapshot);
        }
        public synchronized void finishInspection(String generation,String side,SelectAiInspection.Snapshot snapshot){
            if(plan==null||!plan.generation().equals(generation)||snapshot==null)throw AiAssistant.stale();if("left".equals(side)&&leftInspectionRunning){leftInspection=snapshot;leftInspectionRunning=false;}else if("right".equals(side)&&rightInspectionRunning){rightInspection=snapshot;rightInspectionRunning=false;}else throw AiAssistant.stale();
        }
        public synchronized void failInspection(String generation,String side){
            if(plan==null||!plan.generation().equals(generation))return;
            if("left".equals(side))leftInspectionRunning=false;else if("right".equals(side))rightInspectionRunning=false;
        }
        public synchronized ShowPromptPlan prepareShowPrompt(String language,Instant now){
            busy();if(plan==null)throw AiAssistant.stale();showPrompt=new ShowPromptPlan(plan.generation(),new Preview(UUID.randomUUID().toString(),plan.left().profile(),SelectAiTest.prompt(SelectAiTest.Action.PROMPT,plan.question(),language),now.plusSeconds(600)),new Preview(UUID.randomUUID().toString(),plan.right().profile(),SelectAiTest.prompt(SelectAiTest.Action.PROMPT,plan.question(),language),now.plusSeconds(600)),2);leftPrompt=null;rightPrompt=null;return showPrompt;
        }
        public synchronized Preview consumePrompt(String token,boolean consent,Instant now){
            if(!consent)throw new AiAssistant.Failure(400,"aitest.consentRequired","외부 전송·사용량 확인이 필요합니다.");if(showPrompt==null||!now.isBefore(showPrompt.left().expires()))throw AiAssistant.stale();Preview value=showPrompt.left().token().equals(token)?showPrompt.left():showPrompt.right().token().equals(token)?showPrompt.right():null;if(value==null)throw AiAssistant.stale();if(value==showPrompt.left()){if(leftPromptRunning||leftPrompt!=null)throw AiAssistant.stale();leftPromptRunning=true;}else{if(rightPromptRunning||rightPrompt!=null)throw AiAssistant.stale();rightPromptRunning=true;}return value;
        }
        public synchronized void finishPrompt(String token,SelectAiTest.Outcome value){if(showPrompt==null)throw AiAssistant.stale();if(showPrompt.left().token().equals(token)&&leftPromptRunning){leftPrompt=value;leftPromptRunning=false;}else if(showPrompt.right().token().equals(token)&&rightPromptRunning){rightPrompt=value;rightPromptRunning=false;}else throw AiAssistant.stale();}
        public synchronized Result result(){return new Result(left,right,leftInspection,rightInspection,leftPrompt,rightPrompt);}
        public synchronized Plan plan(){return plan;}
        public synchronized SavePreview prepareSave(String generation,String side,String resultId,Instant now){
            if(plan==null||!plan.generation().equals(generation)||!("left".equals(side)||"right".equals(side)))throw AiAssistant.stale();
            var outcome="left".equals(side)?left:right;if(outcome==null||!outcome.id().equals(resultId))throw AiAssistant.stale();
            String key=saveKey(generation,side,resultId);var old=saves.get(key);if(old!=null){
                if(old.unconfirmed())throw new AiAssistant.Failure(409,"problemQuestion.saveUnconfirmed","저장 결과를 확인할 때까지 새 저장 미리보기를 만들 수 없습니다.");
                if(old.savedId()!=null||old.saving()||now.isBefore(old.preview().expires()))return old.preview();
                // A never-submitted preview may expire; replacing it cannot duplicate a write.
                saves.remove(key);
            }
            if(saves.size()>=MAX_SAVE_LEDGER)throw new AiAssistant.Failure(409,"problemQuestion.saveLedgerFull","저장 확인 기록 한도에 도달했습니다. 현재 세션을 새로고침한 뒤 다시 시도해 주세요.");
            var preview=new SavePreview(UUID.randomUUID().toString(),generation,side,resultId,now.plusSeconds(300));saves.put(key,new SaveSlot(preview,new SaveSelection(outcome,"left".equals(side)?leftInspection:rightInspection,"left".equals(side)?leftPrompt:rightPrompt),null,null,false,false));return preview;
        }
        public synchronized SaveBegin beginSave(String generation,String side,String resultId,String token,String purpose,Instant now){
            var save=saves.get(saveKey(generation,side,resultId));if(save==null||plan==null||!now.isBefore(save.preview().expires())||!save.preview().token().equals(token)||!plan.generation().equals(generation)||save.unconfirmed())throw AiAssistant.stale();
            var outcome="left".equals(side)?left:right;if(outcome==null||!outcome.id().equals(resultId))throw AiAssistant.stale();
            if(save.purpose()!=null&&!save.purpose().equals(purpose))throw AiAssistant.stale();if(save.savedId()!=null)return new SaveBegin(save.selection(),save.savedId());if(save.saving())throw AiAssistant.stale();saves.put(saveKey(generation,side,resultId),new SaveSlot(save.preview(),save.selection(),purpose,null,true,false));return new SaveBegin(save.selection(),null);
        }
        private SaveSlot tokenSave(String token){return saves.values().stream().filter(s->s.preview().token().equals(token)).findFirst().orElseThrow(AiAssistant::stale);}
        public synchronized void finishSave(String token,String id){var save=tokenSave(token);if(!save.saving())throw AiAssistant.stale();saves.put(saveKey(save.preview().generation(),save.preview().side(),save.preview().resultId()),new SaveSlot(save.preview(),save.selection(),save.purpose(),id,false,false));}
        /** A known pre-write/domain failure did not persist: require a newly reviewed destination. */
        public synchronized void abortSave(String token){var save=tokenSave(token);if(!save.saving())throw AiAssistant.stale();saves.remove(saveKey(save.preview().generation(),save.preview().side(),save.preview().resultId()));}
        public synchronized void unconfirmedSave(String token){var save=tokenSave(token);if(!save.saving())throw AiAssistant.stale();saves.put(saveKey(save.preview().generation(),save.preview().side(),save.preview().resultId()),new SaveSlot(save.preview(),save.selection(),save.purpose(),null,false,true));}
        public synchronized AiPreview prepareAi(AiAssistant.Profile profile,Set<String> fields,Instant now){
            busy();if(left==null||right==null||profile==null)throw new AiAssistant.Failure(409,"testComparison.incomplete","두 프로필 결과를 모두 확보한 뒤 AI 비교를 요청해 주세요.");Set<String> allowed=Set.of("sql","error","time","options","prompt","metadata","feedback");if(fields==null||fields.isEmpty()||!allowed.containsAll(fields))throw new AiAssistant.Failure(400,"testComparison.fields","AI 비교에 포함할 자료를 선택해 주세요.");
            String source="Compare only the user-selected Select AI observations. Do not declare a winner, execute SQL, change Feedback, or change profiles. Separate observed differences, possible impact, and follow-up checks.\n"+comparisonSource("A",left,leftInspection,leftPrompt,fields)+"\n"+comparisonSource("B",right,rightInspection,rightPrompt,fields);if(source.length()>AiAssistant.MAX_SOURCE)throw new AiAssistant.Failure(413,"testComparison.tooLong","AI 비교 자료가 너무 큽니다.");ai=new AiPreview(plan.generation(),UUID.randomUUID().toString(),profile,source,now.plusSeconds(600));aiResult=null;return ai;
        }
        private String comparisonSource(String label,SelectAiTest.Outcome sql,SelectAiInspection.Snapshot inspection,SelectAiTest.Outcome prompt,Set<String> fields){
            var out=new StringBuilder(label+" PROFILE: "+sql.profile().selection().name()+"\n");if(fields.contains("sql"))out.append("SQL: ").append(Objects.toString(sql.text(),"NOT_AVAILABLE")).append('\n');if(fields.contains("error"))out.append("ERROR: ").append(Objects.toString(sql.error(),"NOT_AVAILABLE")).append('\n');if(fields.contains("time"))out.append("TIME: ").append(sql.requestedAt()).append(" / ").append(sql.elapsedMillis()).append("ms\n");if(fields.contains("options"))out.append("OPTIONS: ").append(inspection==null?"NOT_OBSERVED":inspection.settings()).append('\n');if(fields.contains("metadata"))out.append("METADATA: ").append(inspection==null?"NOT_OBSERVED":inspection.tables()).append('\n');if(fields.contains("feedback"))out.append("FEEDBACK: ").append(inspection==null||inspection.feedback()==null?"NOT_OBSERVED":Map.of("page",inspection.feedback(),"details",inspection.feedbackDetails())).append('\n');if(fields.contains("prompt"))out.append("SHOWPROMPT: ").append(prompt==null?"NOT_REQUESTED":Objects.toString(prompt.text(),prompt.error())).append('\n');return out.toString();
        }
        public synchronized AiPreview consumeAi(String token,boolean consent,Instant now){if(!consent||ai==null||!ai.token().equals(token)||!now.isBefore(ai.expires()))throw AiAssistant.stale();var value=ai;ai=null;aiRunning=true;return value;}
        public synchronized void finishAi(String generation,String token,String result){if(!aiRunning||plan==null||!plan.generation().equals(generation))throw AiAssistant.stale();aiRunning=false;aiResult=result;}
        public synchronized String aiResult(){return aiResult;}
    }
}
