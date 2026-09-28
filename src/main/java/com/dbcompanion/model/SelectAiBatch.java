package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;

/** Session-only sequential batch state. It has no repository, SQL, or provider dependency. */
public final class SelectAiBatch {
    private SelectAiBatch() {}
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }
    public record Item(String parentId,String question,String profileFingerprint,String token,Status status,String result,String error,Instant requestedAt,long elapsedMillis) {
        public Item(String parentId,String question,String profileFingerprint,String token,Status status,String result,String error) {
            this(parentId,question,profileFingerprint,token,status,result,error,null,0);
        }
    }
    public record Plan(String generation,List<Item> items,Instant expires,AiAssistant.Profile profile) {
        public Plan{items=List.copyOf(items);}
        public Plan(String generation,List<Item> items,Instant expires){this(generation,items,expires,null);}
    }
    public record Start(String generation,Item item) {}
    public record Context(AiAssistant.Profile profile,Instant parentVersion,String locale,String source,String savedAttemptId,boolean saving) {}
    public static final class State {
        private Plan plan;
        private final Map<String,Context> contexts=new HashMap<>();
        private final Set<String> unconfirmedSaves=new HashSet<>();
        public synchronized void context(String token,AiAssistant.Profile profile,Instant parentVersion,String locale,String source){
            if(plan==null||index(token)<0||profile==null||(plan.profile()!=null&&!plan.profile().equals(profile)))throw AiAssistant.stale();
            contexts.put(token,new Context(profile,parentVersion,locale,source,null,false));
            plan=new Plan(plan.generation(),plan.items(),plan.expires(),profile);
        }
        public synchronized Context context(String generation,String token){if(plan==null||!plan.generation().equals(generation)||!contexts.containsKey(token))throw AiAssistant.stale();return contexts.get(token);}
        public synchronized String beginSave(String generation,String token){var c=context(generation,token);if(c.savedAttemptId()!=null)return c.savedAttemptId();if(unconfirmedSaves.contains(token))throw new AiAssistant.Failure(409,"batch.saveUnconfirmed","SAVE_UNCONFIRMED: 이전 저장 결과를 확인해 주세요. 자동 재전송하지 않습니다.");var status=plan.items().get(index(token)).status();if(c.saving||(status!=Status.SUCCEEDED&&status!=Status.FAILED))throw AiAssistant.stale();contexts.put(token,new Context(c.profile(),c.parentVersion(),c.locale(),c.source(),null,true));return null;}
        public synchronized void failedSave(String generation,String token,boolean unconfirmed){var c=context(generation,token);contexts.put(token,new Context(c.profile(),c.parentVersion(),c.locale(),c.source(),null,false));if(unconfirmed)unconfirmedSaves.add(token);}
        public synchronized void finishSave(String generation,String token,String id){var c=context(generation,token);contexts.put(token,new Context(c.profile(),c.parentVersion(),c.locale(),c.source(),id,false));}
        public synchronized Plan prepare(List<Item> source,Instant now){
            if(plan!=null&&(plan.items().stream().anyMatch(i->i.status()==Status.RUNNING)||contexts.values().stream().anyMatch(Context::saving)))throw new AiAssistant.Failure(409,"batch.busy","일괄 생성 요청 처리 중입니다.");
            if(source==null||source.isEmpty()||source.size()>20)throw new AiAssistant.Failure(400,"batch.items","1~20개의 등록 질문을 선택해 주세요.");
            var ids=new HashSet<String>();var items=new ArrayList<Item>();
            for(var value:source){if(value==null||value.parentId()==null||value.question()==null||value.profileFingerprint()==null||value.parentId().isBlank()||value.question().isBlank()||value.profileFingerprint().isBlank()||!ids.add(value.parentId()))throw new AiAssistant.Failure(400,"batch.items","선택한 등록 질문을 확인해 주세요.");items.add(new Item(value.parentId(),value.question(),value.profileFingerprint(),UUID.randomUUID().toString(),Status.PENDING,null,null));}
            contexts.clear();unconfirmedSaves.clear();plan=new Plan(UUID.randomUUID().toString(),items,now.plusSeconds(600));return plan;
        }
        public synchronized Plan plan(){return plan;}
        public synchronized Start consumeNext(String generation,String token,boolean consent,Instant now){
            if(!consent)throw new AiAssistant.Failure(400,"aitest.consentRequired","외부 전송·사용량 확인이 필요합니다.");if(plan==null||!Objects.equals(plan.generation(),generation)||!now.isBefore(plan.expires()))throw AiAssistant.stale();
            int index=index(token);if(index<0||plan.items().get(index).status()!=Status.PENDING||plan.items().stream().anyMatch(i->i.status()==Status.RUNNING)||plan.items().subList(0,index).stream().anyMatch(i->i.status()==Status.PENDING))throw AiAssistant.stale();
            var next=replace(index,Status.RUNNING,null,null,now,0);return new Start(plan.generation(),next);
        }
        public synchronized void finish(String generation,String token,String result,String error){finish(generation,token,result,error,Instant.now());}
        public synchronized void finish(String generation,String token,String result,String error,Instant completedAt){
            if(plan==null||!Objects.equals(plan.generation(),generation))throw AiAssistant.stale();
            int index=index(token);
            if(index<0||plan.items().get(index).status()!=Status.RUNNING||completedAt==null)throw AiAssistant.stale();
            var item=plan.items().get(index);
            long elapsed=Math.max(0,java.time.Duration.between(item.requestedAt(),completedAt).toMillis());
            replace(index,error==null?Status.SUCCEEDED:Status.FAILED,result,error,item.requestedAt(),elapsed);
        }
        public synchronized Plan cancel(String generation){if(plan==null||!Objects.equals(plan.generation(),generation))throw AiAssistant.stale();for(int i=0;i<plan.items().size();i++)if(plan.items().get(i).status()==Status.PENDING)replace(i,Status.SKIPPED,null,"CANCELLED");return plan;}
        private int index(String token){for(int i=0;i<plan.items().size();i++)if(Objects.equals(plan.items().get(i).token(),token))return i;return -1;}
        private Item replace(int index,Status status,String result,String error){var old=plan.items().get(index);return replace(index,status,result,error,old.requestedAt(),old.elapsedMillis());}
        private Item replace(int index,Status status,String result,String error,Instant requestedAt,long elapsedMillis){var copy=new ArrayList<>(plan.items());var old=copy.get(index);var next=new Item(old.parentId(),old.question(),old.profileFingerprint(),old.token(),status,result,error,requestedAt,elapsedMillis);copy.set(index,next);plan=new Plan(plan.generation(),copy,plan.expires(),plan.profile());return next;}
    }
}
