package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.FunctionCatalog.*;
import com.dbcompanion.repository.FunctionCatalogRepository;
import com.dbcompanion.repository.RoutineSourceRepository;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FunctionCatalogService {
    private final SessionDataSource source;
    private final FunctionCatalogRepository repository;
    private final RoutineSourceRepository routines;
    private final TransactionTemplate read;
    public FunctionCatalogService(SessionDataSource source,FunctionCatalogRepository repository,RoutineSourceRepository routines){
        this.source=source;this.repository=repository;this.routines=routines;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    public static void scope(String selected,String requested){
        if(!selected.equals(requested))throw new Failure(409,UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
    }
    private <T>T query(PoolSession session,String schema,Supplier<T> work){
        source.bind(session.pool(),schema);
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public List<Entry> list(PoolSession session,String schema,boolean refresh){
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            return session.metadata().functions(schema,refresh,()->query(session,schema,()->repository.list(schema)));
        }
    }
    public List<Detail> detail(PoolSession session,String schema,String name){
        var reference=SqlObjectName.parts(name);
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            return session.metadata().functionDetails(schema,reference,
                    ()->query(session,schema,()->routines.source(schema,name,2_000_000).definitions().stream().map(repository::detail).toList()));
        }
    }
}
