-- Bind :snapshot_iri (IN capture.sql output), :columns (OUT SYS_REFCURSOR).
-- Requires database JVM support for SEM_MATCH. If ORA-29538 occurs, do not
-- enable JVM implicitly; read-columns.sql is the tested basic SQL alternative.
-- SEM_MATCH needs a literal network owner during SQL describe/parse.
-- Oracle builds that literal safely; no application-side RDF mapping is needed.
DECLARE
  v_snapshot VARCHAR2(100) := :snapshot_iri;
  v_sql CLOB;
BEGIN
  v_sql := q'~SELECT column_name_value AS column_name, datatype_value AS data_type,
       nullable_value AS nullable, comment_value AS comments,
       TO_NUMBER(ordinal_value) AS column_id
FROM TABLE(SEM_MATCH(
  'PREFIX m: <urn:dbc:meta:>
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   SELECT ?capture_graph ?column_name_value ?datatype_value ?nullable_value ?comment_value ?ordinal_value
   WHERE { GRAPH ?capture_graph {
     ?capture_graph m:object ?object_node .
     ?object_node m:hasColumn ?column_node .
     ?column_node m:name ?column_name_value ; m:dataType ?datatype_value ;
                  m:nullable ?nullable_value ; m:ordinal ?ordinal_value .
     OPTIONAL { ?column_node rdfs:comment ?comment_value }
   } }',
  SEM_MODELS('DBC_METADATA'), NULL, NULL, NULL, NULL,
  'DO_UNESCAPE=T', NULL, NULL, ~' || DBMS_ASSERT.ENQUOTE_LITERAL(USER) || q'~, 'DBC_META_RDF'))
WHERE capture_graph = :snapshot
ORDER BY TO_NUMBER(ordinal_value)~';
  OPEN :columns FOR v_sql USING v_snapshot;
END;
