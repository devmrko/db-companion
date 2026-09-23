package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.CatalogOperations;
import com.dbcompanion.model.CatalogOperations.*;
import com.dbcompanion.model.ExternalSources.Failure;
import com.dbcompanion.model.MountedCatalogs.Catalog;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CatalogOperationsService {
    private final SessionDataSource source;
    private final CatalogOperationsRepository repository;
    private final MountedCatalogsRepository mounted;
    private final MountedCatalogsService catalogs;
    private final TransactionTemplate read,write;
    public CatalogOperationsService(SessionDataSource source,CatalogOperationsRepository repository,MountedCatalogsRepository mounted,MountedCatalogsService catalogs){
        this.source=source;this.repository=repository;this.mounted=mounted;this.catalogs=catalogs;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(30);
        write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    private <T>T transaction(PoolSession session,boolean writing,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return (writing?write:read).execute(status->work.get());}finally{source.clear();}
    }
    private State state(PoolSession session){return session.metadata().externalSources().mounted().operations();}
    private static Failure failure(String key,String fallback){return new Failure(409,UiMessages.text(key,fallback));}
    private static void readable(Catalog list){if(!list.status().equals("AVAILABLE"))throw failure("catalogOps.listRequired","등록 목록을 확인할 수 없습니다. 새로고침 후 다시 확인해 주세요.");}
    private void availableName(String name){
        var list=mounted.list();readable(list);
        if(list.items().stream().anyMatch(e->e.name().equalsIgnoreCase(name)))throw failure("catalogOps.duplicate","이미 등록된 카탈로그 이름입니다.");
    }
    public Preview preview(PoolSession session,String owner,String link,String name){
        CatalogOperations.identifier(owner);CatalogOperations.identifier(link);String catalog=CatalogOperations.catalogName(name);
        synchronized(session){
            String user=session.metadata().info().username();
            if(!CatalogOperations.accessibleOwner(user,owner))throw failure("catalogOps.ownerRequired","로그인 계정 소유 또는 PUBLIC DB Link만 등록할 수 있습니다.");
            var prepared=transaction(session,false,()->{
                var resolved=repository.link(user,owner,link);availableName(catalog);
                var api=repository.api();repository.requireMountApi(api);
                var view=new Preview(UUID.randomUUID().toString(),catalog,owner,link,CatalogOperationsRepository.previewSql(api,catalog,link));
                return new Prepared(view,resolved,api.sql(),Instant.now().plusSeconds(300));
            });
            state(session).prepare(prepared);return prepared.view();
        }
    }
    public Receipt mount(PoolSession session,String token){
        synchronized(session){
            Prepared prepared=state(session).consume(token,Instant.now());var attempted=new AtomicBoolean();
            try{
                transaction(session,true,()->{
                    var view=prepared.view();String user=session.metadata().info().username();
                    var current=repository.link(user,view.owner(),view.link());
                    if(!current.equals(prepared.link()))throw failure("catalogOps.linkChanged","DB Link가 변경되었습니다. 등록 내용을 다시 확인해 주세요.");
                    availableName(view.catalog());var api=repository.api();repository.requireMountApi(api);
                    if(!api.sql().equals(prepared.api()))throw failure("catalogOps.linkChanged","DB Link가 변경되었습니다. 등록 내용을 다시 확인해 주세요.");
                    attempted.set(true);repository.mount(api,view.catalog(),view.link());return null;
                });
            }catch(RuntimeException ex){
                if(!attempted.get())throw ex;
                throw failure("catalogOps.uncertain","등록 호출 후 결과를 확정하지 못했습니다. 자동 재시도하지 않습니다. 카탈로그 목록을 새로고침해 확인해 주세요.",ex);
            }finally{if(attempted.get())session.metadata().externalSources().mounted().clear();}
            // Check registration in a separate transaction. Do not report a read failure as a failed mount.
            try{
                var list=catalogs.list(session,true);
                if(!list.status().equals("AVAILABLE")||list.items().stream().noneMatch(e->e.name().equals(prepared.view().catalog())))
                    return new Receipt(prepared.view().catalog(),"UNCONFIRMED",list.error());
                return new Receipt(prepared.view().catalog(),"REGISTERED","");
            }catch(RuntimeException ex){return new Receipt(prepared.view().catalog(),"UNCONFIRMED",ExternalSourcesRepository.error(ex));}
        }
    }
    private static Failure failure(String key,String fallback,RuntimeException ex){return new Failure(409,UiMessages.text(key,fallback)+" · MOUNT_DB_LINK · "+ExternalSourcesRepository.error(ex));}
    public Grid browse(PoolSession session,String name,Level level,String schema,String table){
        CatalogOperations.identifier(name);if(level!=Level.schemas)CatalogOperations.identifier(schema);if(level==Level.columns)CatalogOperations.identifier(table);
        synchronized(session){
            var list=catalogs.list(session,false);readable(list);
            var entry=list.items().stream().filter(e->e.name().equals(name)).findFirst()
                .orElseThrow(()->failure("catalogOps.listRequired","등록 목록을 확인할 수 없습니다. 새로고침 후 다시 확인해 주세요."));
            if(!CatalogOperations.enabled(entry.enabled()))throw failure("catalogOps.disabled","비활성 카탈로그입니다.");
            if(level!=Level.schemas&&!browse(session,name,Level.schemas,null,null).contains("SCHEMA_NAME",schema))throw new IllegalArgumentException("Unknown catalog schema");
            if(level==Level.columns&&!browse(session,name,Level.tables,schema,null).contains("TABLE_NAME",table))throw new IllegalArgumentException("Unknown catalog table");
            var key=new Key(name,level,level==Level.schemas?null:schema,level==Level.columns?table:null);
            return state(session).grid(key,()->transaction(session,false,()->repository.browse(name,level,schema,table)));
        }
    }
}
