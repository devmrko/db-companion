package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OntologyScopeService {
    private final SessionDataSource source;private final OntologyService ontology;private final DatabaseService database;private final AppRecordRepository records;private final JsonMapper json;
    private final TransactionTemplate read,write;
    public OntologyScopeService(SessionDataSource source,OntologyService ontology,DatabaseService database,AppRecordRepository records,JsonMapper json){
        this.source=source;this.ontology=ontology;this.database=database;this.records=records;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(30);write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Ontology.Failure(409,"stale");}
    private <T>T tx(PoolSession s,TransactionTemplate transaction,Supplier<T> action){
        source.bind(s.pool(),login(s));try{return transaction.execute(status->action.get());}finally{source.clear();}
    }
    public record Options(List<Ontology.Summary> tables,int schemaTables,String storage,boolean canSave,String installSql){public Options{tables=List.copyOf(tables);}}
    public Options options(PoolSession s,String schema){synchronized(s){
        scope(s,schema);var catalog=ontology.catalog(s,schema,false);if(!catalog.status().equals("READY"))throw new Ontology.Failure(409,"notReady");
        String storage;try{storage=tx(s,read,()->records.status(schema,login(s)));}catch(RuntimeException ex){storage="UNAVAILABLE";}
        boolean owner=schema.equals(login(s));return new Options(catalog.entries(),catalog.tables().size(),storage,owner&&storage.equals("READY"),owner&&storage.equals("MISSING")?AppRecordSql.create(schema):"");
    }}
    public OntologyScope.Imported fromProfile(PoolSession s,String schema,String profile){synchronized(s){
        scope(s,schema);Ontology.name(profile);var objectList=database.profileObjects(s,schema,profile).objectList();
        return OntologyScope.fromProfile(objectList,schema,ontology.catalog(s,schema,false).entries().stream().map(Ontology.Summary::name).toList(),json);
    }}
    public OntologyScope.Page page(PoolSession s,String schema,String before){synchronized(s){scope(s,schema);return tx(s,read,()->{
        records.require(schema,login(s));return records.scopes(schema,s.metadata().info().database(),before);
    });}}
    public String save(PoolSession s,String schema,String name,List<String> tables){synchronized(s){
        scope(s,schema);QueryArchive.owner(schema,login(s));var selection=new OntologyScope.Selection(1,s.metadata().info().database(),schema,name,tables);
        return tx(s,write,()->{records.require(schema,login(s));String id=UUID.randomUUID().toString();var payload=json.valueToTree(selection);
            records.begin(schema,id,OntologyScope.TYPE,payload);if(!records.finish(schema,id,"SUCCEEDED",payload))throw new Ontology.Failure(409,"verifyFailed");return id;});
    }}
    public Options install(PoolSession s,String schema,boolean confirmed){synchronized(s){
        scope(s,schema);QueryArchive.owner(schema,login(s));if(!confirmed)throw new Ontology.Failure(400,"confirmRequired");
        tx(s,write,()->{records.install(schema,login(s));return true;});s.metadata().queryArchive().forget(schema);return options(s,schema);
    }}
}
