package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SemanticRelease.*;
import com.dbcompanion.repository.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SemanticReleaseService {
    private final SessionDataSource source;private final AppRecordRepository records;private final SemanticReleaseRepository repository;
    private final BusinessGlossaryRepository glossary;private final OntologyRepository ontology;private final PropertyGraphRepository physical;private final JsonMapper json;
    private final TransactionTemplate read,write;
    public SemanticReleaseService(SessionDataSource source,AppRecordRepository records,SemanticReleaseRepository repository,BusinessGlossaryRepository glossary,OntologyRepository ontology,PropertyGraphRepository physical,JsonMapper json){
        this.source=source;this.records=records;this.repository=repository;this.glossary=glossary;this.ontology=ontology;this.physical=physical;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(60);write=new TransactionTemplate(manager);write.setTimeout(60);
    }
    private String owner(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s){if(!owner(s).equals(s.metadata().selectedSchema()))throw SemanticRelease.invalid();}
    private <T>T tx(PoolSession s,boolean writing,Supplier<T> work){source.bind(s.pool(),owner(s));try{return (writing?write:read).execute(x->work.get());}finally{source.clear();}}
    private Version decode(PoolSession s,SemanticReleaseRepository.Row row){var saved=json.readValue(row.payload(),Stored.class);var content=saved.read(json);if(!owner(s).equals(content.owner())||!s.metadata().info().database().equals(content.database()))throw SemanticRelease.invalid();return new Version(row.id(),row.actor(),row.recordedAt(),saved.hash(),content);}
    public Version detail(PoolSession s,String id){synchronized(s){scope(s);return tx(s,false,()->{records.require(owner(s),owner(s));return decode(s,repository.get(owner(s),id));});}}
    public StoragePlan storagePreview(PoolSession s){synchronized(s){scope(s);return tx(s,false,()->{
        if(!records.status(owner(s),owner(s)).equals("MISSING"))throw SemanticRelease.invalid();
        repository.requireStoragePrivilege();
        return s.metadata().semanticRelease().storagePlan(new StoragePlan(UUID.randomUUID().toString(),owner(s),AppRecordSql.create(owner(s)),java.time.Instant.now().plusSeconds(300)));
    });}}
    /** DDL commits independently. Never combine this with snapshot creation or automatic retries. */
    public Status storageInstall(PoolSession s,String token,boolean confirmed){synchronized(s){scope(s);
        s.metadata().semanticRelease().consumeStorage(token,owner(s),confirmed,java.time.Instant.now());
        source.bind(s.pool(),owner(s));
        try{
            var current=records.status(owner(s),owner(s));
            if(current.equals("MISSING")){repository.requireStoragePrivilege();records.install(owner(s),owner(s));}
            else if(!current.equals("READY"))throw SemanticRelease.invalid();
        }catch(org.springframework.dao.DataAccessException ex){
            throw BusinessGlossary.failure(503,"저장소 생성 또는 확인에 실패했습니다. 권한·테이블스페이스 할당량과 생성 여부를 먼저 확인해 주세요. DDL은 자동 커밋되며 자동 재시도하지 않습니다.");
        }finally{source.clear();}
        return status(s);
    }}
    public record Item(String id,long number,String name,String hash,String actor,String recordedAt,int terms,int tables){}
    public record Status(String storage,Head head,Preference preference,String selected,List<Item> versions,List<SemanticReleaseRepository.Row> history){}
    public Status status(PoolSession s){synchronized(s){scope(s);return tx(s,false,()->{
        String storage=records.status(owner(s),owner(s));if(!storage.equals("READY"))return new Status(storage,new Head(0,""),new Preference(0,"DEFAULT"),"",List.of(),List.of());
        // Read snapshots before their number map: concurrent commits must not expose an unnumbered item.
        var rows=repository.list(owner(s),SemanticRelease.TYPE);var numbers=repository.numbers(owner(s));
        var versions=rows.stream().map(row->{var v=decode(s,row);var number=numbers.get(v.id());if(number==null)throw SemanticRelease.invalid();return new Item(v.id(),number,v.content().name(),v.hash(),v.actor(),v.recordedAt(),v.content().terms().size(),v.content().entries().size());}).toList();
        var chosen=s.metadata().semanticRelease().selected();return new Status(storage,repository.head(owner(s)),repository.preference(owner(s)),chosen==null?"":chosen.id(),versions,repository.list(owner(s),SemanticRelease.DEPLOY));
    });}}
    /** Only called outside another bound transaction. Pin the resolved release until explicit selection. */
    public Version active(PoolSession s){
        var state=s.metadata().semanticRelease();if(!state.resolved())tx(s,false,()->{
            String storage=records.status(owner(s),owner(s));
            if(storage.equals("MISSING")){state.select(null);return null;}
            if(!storage.equals("READY"))throw SemanticRelease.invalid();
            var pref=repository.preference(owner(s));state.select(resolve(s,pref.selection()));return null;
        });
        var value=state.selected();if(value!=null)scope(s);return value;
    }
    private Version resolve(PoolSession s,String selection){
        if(selection.equals("LIVE"))return null;
        String id=selection.equals("DEFAULT")?repository.head(owner(s)).releaseId():selection;
        if(id.isEmpty())throw BusinessGlossary.failure(409,"운영 배포 버전이 없습니다. 최초 배포 버전을 생성하거나 개발 모드를 명시적으로 선택해 주세요.");
        return decode(s,repository.get(owner(s),id));
    }
    public Version create(PoolSession s,String name,boolean confirmed){synchronized(s){scope(s);if(!confirmed)throw SemanticRelease.invalid();return tx(s,true,()->{
        return capture(s,name);
    });}}
    private Version capture(PoolSession s,String name){
        records.require(owner(s),owner(s));glossary.requireTable(owner(s));
        // Serialize dictionary edits; ontology revisions are append-only and checked again before save.
        glossary.lockTransfer(owner(s));
        var entries=ontology.relationshipEntries(owner(s),owner(s));var terms=glossary.releaseTerms(owner(s));
        if(entries.isEmpty())throw BusinessGlossary.failure(409,"저장된 온톨로지 정의가 없습니다. 온톨로지에서 원천 정의를 먼저 저장해 주세요. 배포 버전은 생성하지 않았습니다.");
        var content=new Content(1,s.metadata().info().database(),owner(s),name,terms,entries);physical.verifyMetadata(entries);
        for(var entry:entries)if(!entry.equals(ontology.entry(owner(s),entry.document().source().table(),0)))throw SemanticRelease.invalid();
        return store(s,content);
    }
    private Version store(PoolSession s,Content content){
        String body=json.writeValueAsString(content);var saved=new Stored(OntologyQueryService.hash(body),body);saved.read(json);
        String id=UUID.randomUUID().toString();repository.insert(owner(s),id,SemanticRelease.TYPE,saved);repository.assignNumber(owner(s),id);return decode(s,repository.get(owner(s),id));
    }
    /** Explicit setup POST only: creation and first operational pointer commit together. */
    public Version initialize(PoolSession s,boolean confirmed){synchronized(s){scope(s);if(!confirmed)throw SemanticRelease.invalid();return tx(s,true,()->{
        records.require(owner(s),owner(s));var head=repository.head(owner(s));
        if(!head.releaseId().isEmpty())return decode(s,repository.get(owner(s),head.releaseId()));
        var initial=capture(s,"Initial semantic release");repository.deploy(owner(s),head,initial.id(),"Initial deployment");return initial;
    });}}
    public record Catalog(List<BusinessGlossary.Key> terms,List<SourceRef> sources){}
    public Catalog catalog(PoolSession s){synchronized(s){scope(s);return tx(s,false,()->{
        records.require(owner(s),owner(s));glossary.requireTable(owner(s));
        return new Catalog(glossary.keys(owner(s)),ontology.relationshipEntries(owner(s),owner(s)).stream().map(e->new SourceRef(e.document().source().table(),e.revision())).toList());
    });}}
    public Version revise(PoolSession s,String parentId,String name,List<TermRef> terms,List<SourceRef> sources,boolean confirmed){synchronized(s){
        scope(s);if(!confirmed||terms==null||sources==null||terms.size()>BusinessGlossary.MAX_TERMS||sources.size()>100)throw SemanticRelease.invalid();
        return tx(s,true,()->{
            records.require(owner(s),owner(s));var parent=decode(s,repository.get(owner(s),parentId));
            var resolvedTerms=terms.stream().map(ref->{
                var term=parent.content().terms().stream().filter(t->t.id().equals(ref.id())&&t.revision()==ref.revision()).findFirst().orElseGet(()->glossary.revision(owner(s),ref.id(),ref.revision()));
                if(!term.id().equals(ref.id())||term.revision()!=ref.revision())throw SemanticRelease.invalid();return term;
            }).toList();
            var resolvedSources=sources.stream().map(ref->parent.content().entries().stream().filter(e->e.document().source().table().equals(ref.table())&&e.revision()==ref.revision()).findFirst().orElseGet(()->{
                var entry=ontology.entry(owner(s),ref.table(),ref.revision());if(entry==null||entry.revision()!=ref.revision())throw SemanticRelease.invalid();return entry;
            })).toList();
            var content=new Content(1,parent.content().database(),owner(s),name,resolvedTerms,resolvedSources,parent.id());physical.verifyMetadata(resolvedSources);return store(s,content);
        });
    }}
    public Status select(PoolSession s,String selection,Preference expected,boolean confirmed){synchronized(s){synchronized(s.metadata().aiTest()){synchronized(s.metadata().assistant()){scope(s);if(!confirmed||expected==null)throw SemanticRelease.invalid();
        s.metadata().aiTest().idle();s.metadata().assistant().select(s.metadata().assistant().selected());
        var value=tx(s,true,()->{records.require(owner(s),owner(s));var chosen=resolve(s,selection);repository.preference(owner(s),expected,selection);return chosen;});
        s.metadata().businessGlossary().clear();s.metadata().inquiry().clear();s.metadata().aiTest().invalidateRequests();s.metadata().aiTest().evidence().clear();s.metadata().semanticRelease().select(value);return status(s);
    }}}}
    public Status deploy(PoolSession s,String id,Head expected,String reason,boolean confirmed){synchronized(s){scope(s);if(!confirmed||expected==null)throw SemanticRelease.invalid();String note=BusinessGlossary.text(reason,500,true);
        tx(s,true,()->{records.require(owner(s),owner(s));var target=decode(s,repository.get(owner(s),id));physical.verifyMetadata(target.content().entries());return repository.deploy(owner(s),expected,id,note);});return status(s);
    }}
    public String stamp(PoolSession s,String payload){var value=s.metadata().semanticRelease().selected();return value==null?payload:json.writeValueAsString(Map.of("semanticRelease",Map.of("id",value.id(),"hash",value.hash(),"name",value.content().name()),"context",json.readTree(payload)));}
    public static void verifyTerms(Version version,List<BusinessGlossary.Term> terms){if(!version.content().terms().containsAll(terms))throw SemanticRelease.invalid();}
    public static void verifyEntries(Version version,List<Ontology.Entry> entries){if(!version.content().entries().containsAll(entries))throw SemanticRelease.invalid();}
}
