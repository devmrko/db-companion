package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.CredentialCatalog.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CredentialCatalogService {
    private final SessionDataSource source;
    private final CredentialCatalogRepository repository;
    private final TransactionTemplate read;
    public CredentialCatalogService(SessionDataSource source,CredentialCatalogRepository repository){
        this.source=source;this.repository=repository;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    public static void scope(String selected,String requested){
        if(!selected.equals(requested))throw new Failure(409,UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
    }
    private <T>T query(PoolSession session,Supplier<T> work){
        // USER views are always resolved in the actual login's context.
        source.bind(session.pool(),session.metadata().info().username());
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public Catalog list(PoolSession session,String schema,boolean refresh){
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            return session.metadata().credentials(schema,refresh,()->query(session,()->repository.list(schema,schema.equals(session.metadata().info().username()))));
        }
    }
    public Detail detail(PoolSession session,String schema,String name){
        if(name==null||name.isBlank()||name.length()>128||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid credential name");
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            Catalog catalog=list(session,schema,false);
            if(!catalog.status().equals("AVAILABLE"))throw new Failure(409,UiMessages.text("credentials.listRequired","Credential 목록을 먼저 확인해 주세요."));
            Entry entry=catalog.items().stream().filter(item->item.name().equals(name)).findFirst()
                    .orElseThrow(()->new Failure(404,UiMessages.text("credentials.notFound","Credential을 찾지 못했습니다. 목록을 갱신해 주세요.")));
            return session.metadata().credentialDetail(schema,name,()->query(session,()->repository.detail(entry,schema.equals(session.metadata().info().username()))));
        }
    }
}
