package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.CatalogColumnProbe.*;
import com.dbcompanion.model.CatalogOperations.Level;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("catalog-diagnostics")
public class CatalogColumnProbeService {
    private final SessionDataSource source;
    private final CatalogOperationsService catalogs;
    private final CatalogOperationsRepository operations;
    private final CatalogColumnProbeRepository repository;
    private final TransactionTemplate read;
    private final Target target;
    public CatalogColumnProbeService(SessionDataSource source,CatalogOperationsService catalogs,CatalogOperationsRepository operations,
            CatalogColumnProbeRepository repository,@Value("${app.catalog-probe.login}") String login,
            @Value("${app.catalog-probe.catalog}") String catalog,@Value("${app.catalog-probe.schema}") String schema,
            @Value("${app.catalog-probe.table}") String table,@Value("${app.catalog-probe.link}") String link){
        this.source=source;this.catalogs=catalogs;this.operations=operations;this.repository=repository;
        target=new Target(login,catalog,schema,table,link);
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    public Target target(){return target;}
    public static String error(RuntimeException ex){
        String details=OracleErrorDetails.forDisplay(ex);
        return details.isBlank()?ex.getClass().getSimpleName():details;
    }
    public Result inspect(PoolSession session,Step step){
        synchronized(session){
            if(!target.allowed(session.metadata().info().username()))throw new IllegalArgumentException("Configured login required");
            String sql="Parent/link validation";List<String> binds=List.of();
            try{
                var tables=catalogs.browse(session,target.catalog(),Level.tables,target.schema(),null);
                if(tables.rows().stream().noneMatch(row->target.table().equals(row.get("TABLE_NAME"))&&"TABLE".equals(row.get("TABLE_TYPE"))))
                    throw new IllegalArgumentException("Configured table is not a catalog TABLE");
                source.bind(session.pool(),target.login());
                var api=read.execute(status->{operations.link(target.login(),target.login(),target.link());return operations.api();});
                sql=CatalogColumnProbeRepository.sql(step,api,target);binds=CatalogColumnProbeRepository.binds(step,target);
                final String statement=sql;final List<String> values=binds;
                var result=read.execute(status->repository.query(step,statement,values));
                return new Result(step,Instant.now(),sql,binds,result,"");
            }catch(RuntimeException ex){
                return new Result(step,Instant.now(),sql,binds,null,error(ex));
            }finally{source.clear();}
        }
    }
}
