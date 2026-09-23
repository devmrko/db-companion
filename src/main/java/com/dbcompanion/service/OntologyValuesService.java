package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.OntologyValues;
import com.dbcompanion.repository.*;
import java.time.Instant;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OntologyValuesService {
    private final SessionDataSource source;private final OntologyRepository catalog;private final OntologyWizardRepository rows;private final TransactionTemplate read;
    public OntologyValuesService(SessionDataSource source,OntologyRepository catalog,OntologyWizardRepository rows){
        this.source=source;this.catalog=catalog;this.rows=rows;read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    public OntologyValues.Preview lookup(PoolSession session,OntologyValues.Lookup input){synchronized(session){
        Ontology.name(input.schema());Ontology.name(input.table());Ontology.name(input.codeColumn());Ontology.name(input.labelColumn());
        if(!input.schema().equals(session.metadata().selectedSchema()))throw new Ontology.Failure(409,"stale");
        if(!input.confirmed())throw new Ontology.Failure(400,"confirmRequired");
        String login=session.metadata().info().username();source.bind(session.pool(),login);
        try{return read.execute(status->{
            catalog.require(input.schema(),login);var entry=catalog.entry(input.schema(),input.table(),0);
            if(entry==null)throw new Ontology.Failure(404,"notFound");
            var columns=OntologyValues.columns(entry,input);
            return OntologyValues.preview(OntologyValues.type(columns.getFirst().dataType()),rows.sample(entry.document().source(),columns,OntologyValues.ROWS),Instant.now().toString());
        });}finally{source.clear();}
    }}
}
