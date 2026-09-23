package com.dbcompanion.common.db;

import com.dbcompanion.model.QueryArchive;

/** Only application-generated SPARQL is executable. No client query text is accepted. */
public final class RdfQuerySql {
    private RdfQuerySql(){}
    public static final String NETWORK="DBC_RDF", MODEL="DBC_QUERY", VIEW=NETWORK+"#RDFT_"+MODEL;
    public static String construct(String id,boolean result){return "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <"+QueryArchive.graph(id,result)+"> { ?s ?p ?o } }";}
    private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    public static String select(String schema,String id,boolean result){
        com.dbcompanion.model.Ontology.name(schema);
        // SEM_MATCH requires a query literal, not a bind. Its only variable component is a validated UUID.
        return "SELECT COALESCE(SUBJ$RDFCLBT,TO_CLOB(SUBJ$RDFTERM)), COALESCE(PRED$RDFCLBT,TO_CLOB(PRED$RDFTERM)), COALESCE(OBJ$RDFCLBT,TO_CLOB(OBJ$RDFTERM)) FROM TABLE(MDSYS.SEM_MATCH("
            +literal(construct(id,result))+",MDSYS.SEM_MODELS('"+MODEL+"'),NULL,NULL,NULL,NULL,'CONSTRUCT_UNIQUE=T CONSTRUCT_STRICT=T',NULL,NULL,"+literal(schema)+",'"+NETWORK+"')) FETCH FIRST "+(QueryArchive.MAX_TRIPLES+1)+" ROWS ONLY";
    }
    public static String insert(String schema){return "INSERT INTO "+ProfileHistorySql.object(schema,VIEW)+" (TRIPLE) VALUES (MDSYS.SDO_RDF_TRIPLE_S(?,?,?,?,?,?))";}
    public static final String CREATE_NETWORK="BEGIN MDSYS.SEM_APIS.CREATE_RDF_NETWORK(tablespace_name=>?,network_owner=>?,network_name=>'"+NETWORK+"'); END;";
    public static final String CREATE_MODEL="BEGIN MDSYS.SEM_APIS.CREATE_RDF_GRAPH(rdf_graph_name=>'"+MODEL+"',table_name=>NULL,column_name=>NULL,network_owner=>?,network_name=>'"+NETWORK+"'); END;";
    public static String setupPreview(String schema,String tablespace){return CREATE_NETWORK.replace("tablespace_name=>?","tablespace_name=>"+literal(tablespace)).replace("network_owner=>?","network_owner=>"+literal(schema))+"\n/\n"+CREATE_MODEL.replace("network_owner=>?","network_owner=>"+literal(schema))+"\n/";}
}
