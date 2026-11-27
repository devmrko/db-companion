package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SelectAiResultArchiveService {
    private final SessionDataSource source;
    private final AppRecordRepository records;
    private final JsonMapper json;
    private final TransactionTemplate read,write;
    public SelectAiResultArchiveService(SessionDataSource source,AppRecordRepository records,JsonMapper json){
        this.source=source;this.records=records;this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);
        write=new TransactionTemplate(manager);write.setTimeout(15);
    }
    private <T>T tx(PoolSession session,boolean writing,Supplier<T> work){
        synchronized(session){
            var metadata=session.metadata();String schema=metadata.selectedSchema(),login=metadata.info().username();
            AiExecutionHistoryService.requireScope(login,schema,schema);
            source.bind(session.pool(),login);
            try{return (writing?write:read).execute(status->{
                String storage=records.status(schema,login);
                if(!storage.equals("READY"))throw new AiAssistant.Failure(409,"resultArchive.storage","조회 결과 저장소(DBC_APP_RECORD)가 준비되지 않았습니다. 문제 질문 화면에서 공통 저장소 상태를 확인하세요. 기존 테이블을 변경하지 않았습니다. ("+storage+")");
                return work.get();
            });}finally{source.clear();}
        }
    }
    public String save(PoolSession session,String resultId,boolean consent){
        if(!consent)throw new AiAssistant.Failure(400,"resultArchive.consent","표시한 조회 행을 DB에 보관하는 데 동의해 주세요.");
        synchronized(session){
            var state=session.metadata().aiTest();
            synchronized(state){
                state.idle();
                var snapshot=SelectAiResultArchive.capture(session.metadata().selectedSchema(),state.latest(),state.executionResult());
                if(!snapshot.resultId().equals(resultId))throw AiAssistant.stale();
                var payload=json.valueToTree(snapshot);
                if(json.writeValueAsString(payload).length()>QueryArchive.MAX_JSON)
                    throw new AiAssistant.Failure(413,"resultArchive.limit","표시 결과가 보관 한도(1,000,000자)를 초과합니다. 조회 범위를 줄여 주세요. 조회 결과는 현재 화면에 유지됩니다.");
                return tx(session,true,()->{
                    String schema=snapshot.schema();var existing=records.detail(schema,snapshot.id(),SelectAiResultArchive.TYPE);
                    if(existing!=null){if(!"SUCCEEDED".equals(existing.item().state()))throw AiAssistant.stale();return snapshot.id();}
                    records.begin(schema,snapshot.id(),SelectAiResultArchive.TYPE,payload);
                    if(!records.finish(schema,snapshot.id(),"SUCCEEDED",payload))throw AiAssistant.stale();
                    return snapshot.id();
                });
            }
        }
    }
    public SelectAiResultArchive.Page page(PoolSession session,AiExecutionHistory.Query query){
        return tx(session,false,()->records.selectAiResults(session.metadata().selectedSchema(),query));
    }
    public SelectAiResultArchive.Snapshot detail(PoolSession session,String id){
        try{if(!java.util.UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(RuntimeException ex){throw new IllegalArgumentException("Invalid result ID");}
        return tx(session,false,()->{
            String schema=session.metadata().selectedSchema();var record=records.detail(schema,id,SelectAiResultArchive.TYPE);
            if(record==null||!"SUCCEEDED".equals(record.item().state()))throw new AiAssistant.Failure(404,"resultArchive.missing","보관한 조회 결과를 찾을 수 없습니다.");
            var snapshot=json.treeToValue(record.payload(),SelectAiResultArchive.Snapshot.class);
            if(snapshot.version()!=1||!schema.equals(snapshot.schema())||!schema.equals(snapshot.data().actor())||!id.equals(snapshot.id()))throw AiAssistant.stale();
            return snapshot;
        });
    }
}
