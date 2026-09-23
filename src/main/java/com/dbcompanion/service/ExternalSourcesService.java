package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.model.SchemaAcl;
import com.dbcompanion.repository.ExternalSourcesRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExternalSourcesService {
    private final SessionDataSource source;
    private final ExternalSourcesRepository repository;
    private final TransactionTemplate read;
    public ExternalSourcesService(SessionDataSource source,ExternalSourcesRepository repository){
        this.source=source;this.repository=repository;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    private void scope(PoolSession session,String schema){
        if(!session.metadata().selectedSchema().equals(schema))throw new Failure(409,UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
    }
    private <T>T query(PoolSession session,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public Catalog list(PoolSession session,String schema,Kind kind,boolean refresh){
        synchronized(session){
            scope(session,schema);
            return session.metadata().externalSources().list(schema,kind,refresh,()->query(session,()->repository.list(kind,schema,schema.equals(session.metadata().info().username()))));
        }
    }
    public AclCatalog acl(PoolSession session,boolean refresh){
        synchronized(session){return session.metadata().externalSources().acl(refresh,()->query(session,repository::acl));}
    }
    public SchemaAcl.Catalog schemaAcl(PoolSession session,String schema,boolean refresh){
        synchronized(session){
            scope(session,schema);
            return session.metadata().externalSources().schemaAcl(schema,refresh,()->{
                AclCatalog raw=acl(session,false);
                var roles=raw.status().equals("AVAILABLE")&&raw.scope().equals("DATABASE")?query(session,()->repository.roles(schema)):new SchemaAcl.Roles(java.util.List.of(),"NOT_READ","");
                return SchemaAcl.map(schema,raw,roles);
            });
        }
    }
    public Detail detail(PoolSession session,String schema,Kind kind,String owner,String name){
        if(owner==null||owner.isBlank()||owner.length()>128||name==null||name.isBlank()||name.length()>128||owner.indexOf('\0')>=0||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid source name");
        synchronized(session){
            scope(session,schema);Catalog catalog=list(session,schema,kind,false);
            if(!catalog.status().equals("AVAILABLE"))throw new Failure(409,UiMessages.text("external.listRequired","목록을 먼저 확인해 주세요."));
            Entry entry=catalog.items().stream().filter(e->e.owner().equals(owner)&&e.name().equals(name)).findFirst()
                .orElseThrow(()->new Failure(404,UiMessages.text("external.notFound","항목을 찾지 못했습니다. 목록을 갱신해 주세요.")));
            return session.metadata().externalSources().detail(schema,kind,owner,name,()->query(session,()->repository.detail(kind,entry,owner.equals(session.metadata().info().username()))));
        }
    }
}
