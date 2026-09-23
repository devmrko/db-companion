package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SelectAiInspection.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires existing catalog and Feedback readers; no AI calls and no business-row queries. */
@Service
public class SelectAiInspectionService {
    private final SessionDataSource source;
    private final AiAssistantRepository ai;
    private final DatabaseRepository catalog;
    private final AiFeedbackRepository feedback;
    private final TransactionTemplate read;
    public SelectAiInspectionService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository catalog,AiFeedbackRepository feedback){
        this.source=source;this.ai=ai;this.catalog=catalog;this.feedback=feedback;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(15);
    }
    private <T>T query(PoolSession session,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public Snapshot load(PoolSession session,String name,String question){
        SelectAiTest.question(question);var state=session.metadata().aiTest();
        synchronized(state){state.idle();var selected=state.selected();
            if(selected==null||!selected.name().equals(name)||!selected.owner().equals(session.metadata().info().username()))throw SelectAiInspection.stale();
            var result=query(session,()->{
                var profile=ai.profile(selected);
                var s=SelectAiInspection.create(profile,question,catalog.profileAttributes(selected.owner(),true,selected.name()),Instant.now());
                if(!profile.equals(ai.profile(selected)))throw SelectAiInspection.stale();
                // Do not silently drop or shorten a question to make the Feedback filter fit.
                if(question.length()<=500)s=SelectAiInspection.feedback(s,readFeedback(s,question,1));
                else s=SelectAiInspection.feedback(s,new Feedback(question,1,null,false,"SEARCH_TOO_LONG",Instant.now()));
                return s;
            });state.inspection(result);return result;
        }
    }
    private Snapshot verified(PoolSession session,String id){
        var s=session.metadata().aiTest().inspection(id);
        if(!s.profile().selection().owner().equals(session.metadata().info().username())||!s.profile().equals(ai.profile(s.profile().selection())))throw SelectAiInspection.stale();
        return s;
    }
    public Snapshot table(PoolSession session,String id,String owner,String name){
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();var result=query(session,()->{
                var s=verified(session,id);SelectAiInspection.requireObject(s,owner,name);
                Table value;
                try{
                    var object=catalog.objectMetadata(owner,name);
                    if(object==null)value=new Table(owner,name,"",null,List.of(),List.of(),"NOT_LOADED","NOT_VISIBLE",Instant.now());
                    else{
                        var columns=catalog.columns(owner,name);List<AnnotationInfo> annotations=List.of();String status="DISABLED_OR_UNSET";
                        if(SelectAiInspection.enabled(s,"annotations")){
                            try{annotations=catalog.annotations(owner,name,object.type());status="LOADED";}
                            catch(RuntimeException ex){status="ERROR · "+CredentialCatalogRepository.error(ex);}
                        }
                        value=new Table(owner,name,object.type(),object.comment(),columns,annotations,status,null,Instant.now());
                    }
                }catch(RuntimeException ex){value=new Table(owner,name,"",null,List.of(),List.of(),"NOT_LOADED",CredentialCatalogRepository.error(ex),Instant.now());}
                return SelectAiInspection.table(s,value);
            });state.inspection(result);return result;
        }
    }
    private Feedback readFeedback(Snapshot s,String search,int page){
        var p=s.profile().selection();var q=new AiFeedback.Query(p.owner(),p.name(),search,"",page);
        try{
            if(!feedback.tableExists(p.owner(),p.name()))return new Feedback(q.search(),page,null,true,null,Instant.now());
            return new Feedback(q.search(),page,feedback.page(q),false,null,Instant.now());
        }catch(RuntimeException ex){return new Feedback(q.search(),page,null,false,CredentialCatalogRepository.error(ex),Instant.now());}
    }
    public Snapshot feedback(PoolSession session,String id,String search,int page){
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();var result=query(session,()->{var s=verified(session,id);return SelectAiInspection.feedback(s,readFeedback(s,search,page));});
            state.inspection(result);return result;
        }
    }
    public Snapshot feedbackDetail(PoolSession session,String id,String rowId){
        AiFeedback.rowId(rowId);var state=session.metadata().aiTest();synchronized(state){
            state.idle();var result=query(session,()->{
                var s=verified(session,id);var f=s.feedback();
                if(f==null||f.rows()==null||f.rows().items().stream().noneMatch(i->i.id().equals(rowId)))throw SelectAiInspection.stale();
                var p=s.profile().selection();var detail=feedback.detail(new AiFeedback.Query(p.owner(),p.name(),f.search(),"",f.page()),rowId);
                if(detail==null)throw SelectAiInspection.stale();
                return SelectAiInspection.detail(s,new FeedbackDetail(rowId,detail,Instant.now()));
            });state.inspection(result);return result;
        }
    }
}
