-- Bind :snapshot_iri (IN), :columns (OUT SYS_REFCURSOR).
-- Native RDF SQL access without SEM_MATCH / the database JVM.
-- SQL aggregates the stored triples; the application does not rebuild the RDF.
DECLARE
  v_snapshot VARCHAR2(100) := :snapshot_iri;
BEGIN
  IF SYS_CONTEXT('USERENV','CURRENT_SCHEMA') <> USER THEN
    RAISE_APPLICATION_ERROR(-20001, 'Connect as the intended RDF owner.');
  END IF;
  OPEN :columns FOR
    WITH terms AS (
      SELECT a.TRIPLE.GET_SUBJECT(USER,'DBC_META_RDF') AS subject,
             a.TRIPLE.GET_PROPERTY(USER,'DBC_META_RDF') AS predicate,
             a.TRIPLE.GET_OBJ_VALUE(USER,'DBC_META_RDF') AS term
      FROM "DBC_META_RDF#RDFT_DBC_METADATA" a
      WHERE a.TRIPLE.GET_MODEL(USER,'DBC_META_RDF') = 'DBC_METADATA:<' || v_snapshot || '>'
    ), triples AS (
      SELECT subject,predicate,
        CASE WHEN SUBSTR(term,1,1)='"' AND SUBSTR(term,-1,1)='"'
          THEN SEM_APIS.UNESCAPE_RDF_VALUE(SUBSTR(term,2,LENGTH(term)-2)) END AS value
      FROM terms
    ), columns AS (
      SELECT subject,
        MAX(CASE WHEN predicate='<urn:dbc:meta:name>' THEN value END) AS column_name,
        MAX(CASE WHEN predicate='<urn:dbc:meta:dataType>' THEN value END) AS data_type,
        MAX(CASE WHEN predicate='<urn:dbc:meta:nullable>' THEN value END) AS nullable,
        MAX(CASE WHEN predicate='<http://www.w3.org/2000/01/rdf-schema#comment>' THEN value END) AS comments,
        MAX(CASE WHEN predicate='<urn:dbc:meta:ordinal>' THEN value END) AS column_id
      FROM triples GROUP BY subject
    )
    SELECT column_name,data_type,nullable,comments,TO_NUMBER(column_id) AS column_id
    FROM columns WHERE data_type IS NOT NULL
    ORDER BY TO_NUMBER(column_id);
END;
