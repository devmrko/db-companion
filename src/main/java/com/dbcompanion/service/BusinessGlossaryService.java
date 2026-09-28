package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.repository.BusinessGlossaryRepository;
import com.dbcompanion.repository.BusinessGlossaryHistoryRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BusinessGlossaryService {
    private final SessionDataSource source;private final BusinessGlossaryRepository repository;private final JsonMapper json;
    private final TransactionTemplate read,write;
    private final BusinessGlossaryHistoryRepository history;
    public BusinessGlossaryService(SessionDataSource source,BusinessGlossaryRepository repository,JsonMapper json){
        this(source,repository,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessGlossaryService(SessionDataSource source,BusinessGlossaryRepository repository,JsonMapper json,BusinessGlossaryHistoryRepository history){
        this.history=history;
        this.source=source;this.repository=repository;this.json=json;var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(30);write=new TransactionTemplate(manager);write.setTimeout(60);
    }
    private String owner(PoolSession session){return session.metadata().info().username();}
    private <T>T query(PoolSession s,boolean writing,Supplier<T> work){source.bind(s.pool(),owner(s));try{return (writing?write:read).execute(x->work.get());}finally{source.clear();}}
    public Status status(PoolSession s){return query(s,false,()->{String table=repository.tableStatus(owner(s));return new Status(owner(s),table,table.equals("READY")?repository.textStatus(owner(s)):"UNAVAILABLE","로그인 소유 스키마의 독립 사전입니다. 설정은 자동 변경하지 않습니다.");});}
    /** Dictionary search checks its own index; ontology analysis is independent. */
    private Analysis analysis(String owner,String question){
        String status=repository.textStatus(owner);
        if(!status.equals("READY"))throw BusinessGlossary.failure(409,"Oracle Text가 준비되지 않았습니다. 사전 관리에서 설정을 확인해 주세요.");
        var tokens=repository.tokens(question);BusinessGlossary.textQuery(tokens);
        return new Analysis("ORACLE_TEXT",tokens);
    }
    public Page page(PoolSession s,String filter,int offset){if(offset<0||offset>100000)throw BusinessGlossary.invalid();String value=BusinessGlossary.text(filter,256,false);return query(s,false,()->{repository.requireTable(owner(s));return repository.page(owner(s),value,offset);});}
    public Term save(PoolSession s,String id,long revision,Draft value,boolean consent){
        if(!consent||value==null)throw BusinessGlossary.failure(400,"사전에 저장할 내용을 확인해 주세요.");
        var result=query(s,true,()->{
            String owner=owner(s);repository.requireTable(owner);Objects.requireNonNull(history).require(owner);
            Term before=id==null||id.isBlank()?null:repository.find(owner,id);
            if(before!=null&&before.revision()!=revision)throw BusinessGlossary.stale();
            var saved=repository.save(owner,id,revision,value);history.append(owner,before,saved);return saved;
        });
        s.metadata().businessGlossary().clear();return result;
    }
    public String historyStatus(PoolSession s){return query(s,false,()->Objects.requireNonNull(history).status(owner(s)));}
    public com.dbcompanion.model.BusinessGlossaryHistory.Page history(PoolSession s,String id,String before){
        BusinessGlossary.id(id);
        return query(s,false,()->Objects.requireNonNull(history).page(owner(s),id,before));
    }
    public Setup setupPreview(PoolSession s,String operation){
        Status status=status(s);var statements=switch(operation){
            case "TABLE"->{if(!status.table().equals("MISSING"))throw BusinessGlossary.stale();yield List.of(BusinessGlossarySql.create(owner(s)));}
            case "TEXT"->{if(!status.table().equals("READY")||!status.text().equals("MISSING"))throw BusinessGlossary.stale();yield BusinessGlossarySql.textSetup(owner(s));}
            case "HISTORY"->{if(!historyStatus(s).equals("MISSING"))throw BusinessGlossary.stale();yield List.of(AppRecordSql.create(owner(s)));}
            default->throw BusinessGlossary.invalid();};
        return s.metadata().businessGlossary().setup(new Setup(UUID.randomUUID().toString(),owner(s),operation,statements,Instant.now().plusSeconds(300)));
    }
    public Status setup(PoolSession s,String token,boolean consent){
        var setup=s.metadata().businessGlossary().consume(token,owner(s),consent,Instant.now());
        query(s,true,()->{if(setup.operation().equals("HISTORY"))Objects.requireNonNull(history).install(owner(s));else repository.install(owner(s),setup.operation());return null;});s.metadata().businessGlossary().clear();return status(s);
    }
    public Search search(PoolSession s,String question,String profile,boolean useText){
        SelectAiTest.question(question);String requestedProfile=BusinessGlossary.text(profile,128,false);
        // A standalone dictionary search needs no profile. Select AI selections are bound when a snapshot is requested.
        return query(s,false,()->{
            String owner=owner(s);repository.requireTable(owner);
            var analysis=useText?analysis(owner,question):Analysis.exact();
            List<Token> tokens=analysis.tokens();String textQuery=useText?BusinessGlossary.textQuery(tokens):"";
            var targets=useText?repository.phraseTargets(owner,question,textQuery):BusinessGlossary.exactTargets(question,repository.keys(owner));
            var ids=new LinkedHashSet<String>();targets.forEach(t->ids.addAll(t.termIds()));
            var hits=new ArrayList<Hit>();for(String id:ids.stream().limit(BusinessGlossary.MAX_HITS).toList()){
                Term term=repository.find(owner,id);if(!term.enabled())throw BusinessGlossary.stale();
                var exact=targets.stream().filter(t->t.termIds().contains(id)).toList();
                hits.add(new Hit(term,exact.isEmpty()?"TEXT":exact.stream().anyMatch(t->t.kind().equals("TERM"))?"TERM":"ALIAS",exact.stream().map(Target::expression).toList()));
            }
            return s.metadata().businessGlossary().remember(new Search(UUID.randomUUID().toString(),owner,requestedProfile,question,useText?"ORACLE_TEXT":"EXACT_ALIAS",targets,tokens,textQuery,hits,ids.size()>BusinessGlossary.MAX_HITS,Instant.now().plusSeconds(900)));
        });
    }
    public Snapshot resolve(PoolSession s,String question,String profile,Selection selection){
        if(selection==null||!selection.enabled())return null;
        var search=s.metadata().businessGlossary().resolve(selection.searchId(),owner(s),profile,question,Instant.now());
        if(search.more())throw BusinessGlossary.failure(413,"검색 결과가 한도를 초과했습니다. 일부만 첨부하지 않습니다. 질문 범위를 좁혀 주세요.");
        if(selection.termIds().size()>BusinessGlossary.MAX_SELECTED||selection.termIds().size()!=search.hits().size()||new HashSet<>(selection.termIds()).size()!=selection.termIds().size())throw BusinessGlossary.invalid();
        var selected=new ArrayList<Hit>();for(String id:selection.termIds())selected.add(search.hits().stream().filter(h->h.term().id().equals(id)).findFirst().orElseThrow(BusinessGlossary::stale));
        var result=BusinessGlossary.snapshot(search,selected,json,Instant.now());verify(s,result);return result;
    }
    public void verify(PoolSession s,Snapshot snapshot){
        if(snapshot==null)return;if(!owner(s).equals(snapshot.owner()))throw BusinessGlossary.stale();
        query(s,false,()->{repository.requireTable(owner(s));for(var hit:snapshot.selected()){
            var current=repository.find(owner(s),hit.term().id());if(!current.enabled()||!current.equals(hit.term()))throw BusinessGlossary.stale();
        }return null;});
    }
}
