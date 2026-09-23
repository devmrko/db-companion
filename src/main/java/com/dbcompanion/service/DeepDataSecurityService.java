package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.DeepDataSecurity.*;
import com.dbcompanion.repository.DeepDataSecurityRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeepDataSecurityService {
    private final SessionDataSource source;
    private final DeepDataSecurityRepository repository;
    private final TransactionTemplate read;
    public DeepDataSecurityService(SessionDataSource source,DeepDataSecurityRepository repository){
        this.source=source;this.repository=repository;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    public static void scope(String selected,String requested){
        if(!selected.equals(requested))throw new Failure(409,UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
    }
    public static void identifier(String value){
        if(value==null||value.isEmpty()||value.length()>128||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid dictionary identifier");
    }
    private <T>T query(PoolSession session,String schema,Supplier<T> work){
        source.bind(session.pool(),schema);
        try{return read.execute(status->work.get());}finally{source.clear();}
    }
    public Dataset list(PoolSession session,String schema,Kind kind,boolean refresh){
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            return session.metadata().security(kind,schema,refresh,()->query(session,schema,()->repository.list(kind,schema)));
        }
    }
    public Detail detail(PoolSession session,String schema,Kind kind,String name,String owner){
        identifier(name);if(kind==Kind.grants)identifier(owner);
        synchronized(session){
            scope(session.metadata().selectedSchema(),schema);
            return query(session,schema,()->repository.detail(kind,schema,name,owner));
        }
    }
}
