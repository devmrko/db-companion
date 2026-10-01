package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.VectorSearch.*;
import com.dbcompanion.repository.ProfileEditRepository;
import com.dbcompanion.repository.VectorSearchRepository;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.model.VectorSearch.*;

@Service
public class VectorSearchService {
    private final SessionDataSource source;
    private final VectorSearchRepository repository;
    private final ProfileEditRepository credentials;
    private final TransactionTemplate read, search;
    private final JsonMapper mapper;
    private final com.dbcompanion.repository.OciEmbeddingModelRepository ociModels;
    public VectorSearchService(SessionDataSource source,VectorSearchRepository repository,ProfileEditRepository credentials,JsonMapper mapper,
            com.dbcompanion.repository.OciEmbeddingModelRepository ociModels) {
        this.source=source; this.repository=repository; this.credentials=credentials; this.mapper=mapper;this.ociModels=ociModels;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        search=new TransactionTemplate(manager);search.setReadOnly(true);search.setTimeout(30);
    }
    private <T>T transaction(PoolSession session,boolean searching,Supplier<T> work) {
        return transaction(session,searching,session.metadata().selectedSchema(),work);
    }
    private <T>T transaction(PoolSession session,boolean searching,String schema,Supplier<T> work) {
        source.bind(session.pool(),schema);
        try { return (searching?search:read).execute(status->work.get()); }
        finally { source.clear(); }
    }
    public List<Table> tables(PoolSession session,boolean refresh) {
        synchronized(session) {
            var state=session.metadata(); String schema=state.selectedSchema();
            return state.vectorTables(schema,refresh,()->transaction(session,false,()->repository.tables(schema,state.info().username())));
        }
    }
    public record Metadata(List<Column> columns,Options options) {}
    public com.dbcompanion.model.OciEmbeddingModels.Result ociModels(PoolSession session,com.dbcompanion.model.OciEmbeddingModels.Request request) {
        synchronized(session) {
            // SDK credentials belong to the authenticated user, not the table's selected schema.
            return session.metadata().ociModels(request,()->transaction(session,true,session.metadata().info().username(),()->{
                if(!credentials.credentialAvailable(request.query().credential()))throw new Failure(409,
                        UiMessages.text("ui.411713619134","선택한 Credential이 없거나 비활성 상태입니다. 옵션을 새로고침해 주세요."));
                return ociModels.list(request);
            }));
        }
    }
    public Metadata metadata(PoolSession session,String schema,String table,boolean refresh) {
        name(schema);name(table);
        synchronized(session) {
            scope(session.metadata().selectedSchema(),schema);
            return transaction(session,false,()->new Metadata(repository.columns(schema,table),session.metadata().vectorOptions(refresh,()-> {
                List<Model> models=List.of(); List<String> names=List.of();String modelError=null,credentialError=null;
                try { models=repository.models(); } catch(RuntimeException ex) { modelError=OracleErrorDetails.forDisplay(ex); }
                try { names=credentials.credentials(); } catch(RuntimeException ex) { credentialError=OracleErrorDetails.forDisplay(ex); }
                return new Options(models,names,modelError,credentialError);
            })));
        }
    }
    public Rows rows(PoolSession session,Selection selection,int page) {
        page(page);
        synchronized(session) {
            scope(session.metadata().selectedSchema(),selection.schema());
            return transaction(session,false,()->repository.rows(selection,repository.columns(selection.schema(),selection.table()),page,null,null,0));
        }
    }
    public List<Value> detail(PoolSession session,Selection selection,String id) {
        rowId(id);
        synchronized(session) {
            scope(session.metadata().selectedSchema(),selection.schema());
            return transaction(session,false,()->repository.detail(selection,repository.columns(selection.schema(),selection.table()),id));
        }
    }
    public Rows search(PoolSession session,Search request) {
        synchronized(session) {
            var selection=request.selection(); scope(session.metadata().selectedSchema(),selection.schema());
            String login=session.metadata().info().username();
            // External credentials resolve in the login's schema; table references remain qualified.
            return transaction(session,true,request.executionSchema(login),()-> {
                var columns=repository.columns(selection.schema(),selection.table());columns(selection,columns);
                if(request.external()) {
                    if(!credentials.credentialAvailable(request.credential())) throw new Failure(409,UiMessages.text("ui.411713619134", "선택한 Credential이 없거나 비활성 상태입니다. 옵션을 새로고침해 주세요."));
                } else if(!repository.modelAvailable(request.modelOwner(),request.model())) throw new Failure(409,UiMessages.text("ui.d669ef5148d4", "선택한 DB 임베딩 모델이 없거나 접근할 수 없습니다. 옵션을 새로고침해 주세요."));
                String params=request.external()?mapper.writeValueAsString(request.externalParams()):null;
                String vector=repository.embedding(request,login,params);
                return repository.rows(selection,columns,1,vector,request.metric(),request.k());
            });
        }
    }
}
