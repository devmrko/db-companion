package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.ExternalSources.Failure;
import com.dbcompanion.model.MountedCatalogs.*;
import com.dbcompanion.repository.MountedCatalogsRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MountedCatalogsService {
    private final SessionDataSource source;
    private final MountedCatalogsRepository repository;
    private final TransactionTemplate read;
    public MountedCatalogsService(SessionDataSource source,MountedCatalogsRepository repository){
        this.source=source;this.repository=repository;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    private <T>T query(PoolSession session,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public Catalog list(PoolSession session,boolean refresh){
        synchronized(session){return session.metadata().externalSources().mounted().list(refresh,()->query(session,repository::list));}
    }
    public Detail detail(PoolSession session,String name){
        if(name==null||name.isBlank()||name.length()>128||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid catalog name");
        synchronized(session){
            var catalog=list(session,false);
            if(!catalog.status().equals("AVAILABLE"))throw new Failure(409,UiMessages.text("external.listRequired","목록을 먼저 확인해 주세요."));
            var entry=catalog.items().stream().filter(e->e.name().equals(name)).findFirst()
                .orElseThrow(()->new Failure(404,UiMessages.text("external.notFound","항목을 찾지 못했습니다. 목록을 갱신해 주세요.")));
            return session.metadata().externalSources().mounted().detail(name,()->query(session,()->repository.detail(entry)));
        }
    }
}
