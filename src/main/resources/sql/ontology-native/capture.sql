-- Bind :object_name (IN exact name), :snapshot_iri (OUT), :triple_count (OUT).
-- Own table/view metadata only; never queries business rows or calls AI.
-- A new named graph is appended per capture. Caller verifies, then commits.
DECLARE
  v_name VARCHAR2(128) := :object_name;
  v_type VARCHAR2(23);
  v_comment VARCHAR2(4000);
  v_snapshot VARCHAR2(100) := 'urn:dbc:capture:' || LOWER(RAWTOHEX(SYS_GUID()));
  v_object VARCHAR2(2000);
  v_column VARCHAR2(3000);
  v_count PLS_INTEGER := 0;
  v_sql VARCHAR2(4000);
  FUNCTION identifier(p_value VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN RAWTOHEX(UTL_I18N.STRING_TO_RAW(p_value, 'AL32UTF8'));
  END;
  PROCEDURE triple(p_subject VARCHAR2, p_predicate VARCHAR2, p_object CLOB) IS
  BEGIN
    EXECUTE IMMEDIATE v_sql USING 'DBC_METADATA:<' || v_snapshot || '>',
      '<' || p_subject || '>', '<' || p_predicate || '>', p_object, USER, 'DBC_META_RDF';
    v_count := v_count + 1;
  END;
  PROCEDURE link(p_subject VARCHAR2, p_predicate VARCHAR2, p_object VARCHAR2) IS
  BEGIN
    -- Oracle accepts CLOB for literal objects, but URI objects must be VARCHAR2.
    EXECUTE IMMEDIATE v_sql USING 'DBC_METADATA:<' || v_snapshot || '>',
      '<' || p_subject || '>', '<' || p_predicate || '>', '<' || p_object || '>', USER, 'DBC_META_RDF';
    v_count := v_count + 1;
  END;
  PROCEDURE literal(p_subject VARCHAR2, p_predicate VARCHAR2, p_value VARCHAR2) IS
  BEGIN
    IF p_value IS NOT NULL THEN
      triple(p_subject, p_predicate,
        TO_CLOB('"') || SEM_APIS.ESCAPE_RDF_VALUE(p_value, 0, 1, NULL, 32767) || '"');
    END IF;
  END;
BEGIN
  IF SYS_CONTEXT('USERENV','CURRENT_SCHEMA') <> USER THEN
    RAISE_APPLICATION_ERROR(-20001, 'Connect as the intended RDF owner.');
  END IF;
  SELECT OBJECT_TYPE INTO v_type FROM USER_OBJECTS
   WHERE OBJECT_NAME = v_name AND OBJECT_TYPE IN ('TABLE','VIEW');
  SELECT COMMENTS INTO v_comment FROM USER_TAB_COMMENTS WHERE TABLE_NAME = v_name;
  v_object := 'urn:dbc:object:' || identifier(USER) || ':' || identifier(v_name);
  v_sql := 'INSERT INTO ' || DBMS_ASSERT.ENQUOTE_NAME(USER, FALSE)
    || '."DBC_META_RDF#RDFT_DBC_METADATA" (TRIPLE) '
    || 'VALUES (MDSYS.SDO_RDF_TRIPLE_S(:g,:s,:p,:o,:owner,:network))';
  link(v_snapshot, 'urn:dbc:meta:object', v_object);
  literal(v_snapshot, 'urn:dbc:meta:capturedBy', SYS_CONTEXT('USERENV','SESSION_USER'));
  literal(v_snapshot, 'urn:dbc:meta:capturedAt',
    TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD"T"HH24:MI:SS.FF6"Z"'));
  link(v_object, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type', 'urn:dbc:meta:' || v_type);
  literal(v_object, 'urn:dbc:meta:owner', USER);
  literal(v_object, 'urn:dbc:meta:name', v_name);
  literal(v_object, 'http://www.w3.org/2000/01/rdf-schema#comment', v_comment);
  FOR c IN (
    SELECT t.COLUMN_NAME,t.COLUMN_ID,t.DATA_TYPE,t.DATA_TYPE_OWNER,t.DATA_LENGTH,
           t.CHAR_LENGTH,t.CHAR_USED,t.DATA_PRECISION,t.DATA_SCALE,t.NULLABLE,c.COMMENTS
      FROM USER_TAB_COLUMNS t LEFT JOIN USER_COL_COMMENTS c
        ON c.TABLE_NAME=t.TABLE_NAME AND c.COLUMN_NAME=t.COLUMN_NAME
     WHERE t.TABLE_NAME=v_name ORDER BY t.COLUMN_ID
  ) LOOP
    v_column := v_object || ':column:' || identifier(c.COLUMN_NAME);
    link(v_object, 'urn:dbc:meta:hasColumn', v_column);
    link(v_column, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type', 'urn:dbc:meta:Column');
    literal(v_column, 'urn:dbc:meta:name', c.COLUMN_NAME);
    literal(v_column, 'urn:dbc:meta:ordinal', TO_CHAR(c.COLUMN_ID, 'TM9'));
    literal(v_column, 'urn:dbc:meta:dataType', c.DATA_TYPE);
    literal(v_column, 'urn:dbc:meta:dataTypeOwner', c.DATA_TYPE_OWNER);
    literal(v_column, 'urn:dbc:meta:dataLength', TO_CHAR(c.DATA_LENGTH, 'TM9'));
    literal(v_column, 'urn:dbc:meta:charLength', TO_CHAR(c.CHAR_LENGTH, 'TM9'));
    literal(v_column, 'urn:dbc:meta:charUsed', c.CHAR_USED);
    literal(v_column, 'urn:dbc:meta:precision', TO_CHAR(c.DATA_PRECISION, 'TM9'));
    literal(v_column, 'urn:dbc:meta:scale', TO_CHAR(c.DATA_SCALE, 'TM9'));
    literal(v_column, 'urn:dbc:meta:nullable', c.NULLABLE);
    literal(v_column, 'http://www.w3.org/2000/01/rdf-schema#comment', c.COMMENTS);
  END LOOP;
  :snapshot_iri := v_snapshot;
  :triple_count := v_count;
END;
