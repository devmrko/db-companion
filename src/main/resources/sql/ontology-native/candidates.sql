-- Bind :snapshot_json (IN CLOB: selected capture IRI array), :candidates (OUT SYS_REFCURSOR).
-- Hypotheses only: matching exact column names and data types is NOT an FK,
-- a unique key, cardinality proof, or a complete multi-column join condition.
-- Reads stored native RDF, never business rows; discovery is Oracle SQL/SPARQL.
DECLARE
  v_selection CLOB := :snapshot_json;
  v_sql CLOB;
BEGIN
  v_sql := q'~WITH selected_captures AS (
  SELECT snapshot_iri FROM JSON_TABLE(:selection, '$[*]'
    COLUMNS(snapshot_iri VARCHAR2(100) PATH '$' ERROR ON ERROR))
), rdf_columns AS (
  SELECT r.* FROM TABLE(SEM_MATCH(
    'PREFIX m: <urn:dbc:meta:>
     SELECT ?capture_graph ?object_name ?column_name_value ?datatype_value ?type_owner
     WHERE { GRAPH ?capture_graph {
       ?capture_graph m:object ?object_node .
       ?object_node m:name ?object_name ; m:hasColumn ?column_node .
       ?column_node m:name ?column_name_value ; m:dataType ?datatype_value .
       OPTIONAL { ?column_node m:dataTypeOwner ?type_owner }
     } }', SEM_MODELS('DBC_METADATA'), NULL, NULL, NULL, NULL,
     'DO_UNESCAPE=T', NULL, NULL, ~' || DBMS_ASSERT.ENQUOTE_LITERAL(USER) || q'~, 'DBC_META_RDF')) r
  JOIN selected_captures s ON s.snapshot_iri=r.capture_graph
)
SELECT DISTINCT a.object_name AS source_table, b.object_name AS target_table,
       a.column_name_value AS source_column, b.column_name_value AS target_column,
       a.datatype_value AS data_type, a.capture_graph AS source_snapshot,
       b.capture_graph AS target_snapshot
FROM rdf_columns a JOIN rdf_columns b
  ON a.object_name < b.object_name
 AND a.column_name_value=b.column_name_value
 AND a.datatype_value=b.datatype_value
 AND (a.type_owner=b.type_owner OR (a.type_owner IS NULL AND b.type_owner IS NULL))
WHERE a.type_owner IS NULL
  AND a.datatype_value IN ('CHAR','NCHAR','VARCHAR2','NVARCHAR2','NUMBER','FLOAT',
    'BINARY_FLOAT','BINARY_DOUBLE','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE',
    'TIMESTAMP WITH LOCAL TIME ZONE','RAW')
ORDER BY source_table,target_table,source_column,target_column
FETCH FIRST 2001 ROWS ONLY~';
  OPEN :candidates FOR v_sql USING v_selection;
END;
