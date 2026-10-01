-- Bind :snapshots (OUT SYS_REFCURSOR). Latest native capture per table/view.
DECLARE
  v_sql CLOB;
BEGIN
  v_sql := q'~SELECT capture_graph, object_name, object_kind, captured_at, captured_by
FROM (
  SELECT r.*, ROW_NUMBER() OVER (
    PARTITION BY object_name ORDER BY captured_at DESC, capture_graph DESC) rn
  FROM TABLE(SEM_MATCH(
    'PREFIX m: <urn:dbc:meta:>
     PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
     SELECT ?capture_graph ?object_name ?object_kind ?captured_at ?captured_by
     WHERE { GRAPH ?capture_graph {
       ?capture_graph m:object ?object_node ; m:capturedAt ?captured_at ; m:capturedBy ?captured_by .
       ?object_node m:name ?object_name ; rdf:type ?object_kind .
     } }', SEM_MODELS('DBC_METADATA'), NULL, NULL, NULL, NULL,
     'DO_UNESCAPE=T', NULL, NULL, ~' || DBMS_ASSERT.ENQUOTE_LITERAL(USER) || q'~, 'DBC_META_RDF')) r
)
WHERE rn=1 ORDER BY object_name FETCH FIRST 501 ROWS ONLY~';
  OPEN :snapshots FOR v_sql;
END;
