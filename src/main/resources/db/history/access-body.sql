CREATE PACKAGE BODY {{OWNER}}."DBC_METADATA_ACCESS" AS
  -- No arbitrary SQL, grants, installation or code replacement is exposed to callers.
  FUNCTION q(p_name VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    IF p_name IS NULL OR LENGTHB(p_name) > 128 OR INSTR(p_name, CHR(0)) > 0 THEN
      RAISE_APPLICATION_ERROR(-20085, 'Invalid history identifier');
    END IF;
    RETURN '"' || REPLACE(p_name, '"', '""') || '"';
  END;

  FUNCTION permitted(p_schema VARCHAR2, p_table VARCHAR2, p_manage BOOLEAN) RETURN BOOLEAN IS
    v_read CHAR(1); v_manage CHAR(1);
  BEGIN
    IF SYS_CONTEXT('USERENV','SESSION_USER') = $$PLSQL_UNIT_OWNER THEN RETURN TRUE; END IF;
    SELECT CAN_READ, CAN_MANAGE INTO v_read, v_manage FROM {{OWNER}}."DBC_MH_ACCESS"
      WHERE SCHEMA_NAME = p_schema AND TABLE_NAME = p_table
        AND GRANTEE IN (SYS_CONTEXT('USERENV','SESSION_USER'), 'PUBLIC')
      ORDER BY CASE WHEN GRANTEE = 'PUBLIC' THEN 1 ELSE 0 END FETCH FIRST 1 ROW ONLY;
    RETURN CASE WHEN p_manage THEN v_manage = 'Y' ELSE v_read = 'Y' END;
  EXCEPTION WHEN NO_DATA_FOUND THEN RETURN FALSE;
  END;

  FUNCTION source_text(p_owner VARCHAR2, p_name VARCHAR2, p_type VARCHAR2) RETURN CLOB IS
    v_result CLOB;
  BEGIN
    DBMS_LOB.CREATETEMPORARY(v_result, TRUE);
    FOR r IN (SELECT TEXT FROM SYS.ALL_SOURCE WHERE OWNER = p_owner AND NAME = p_name AND TYPE = p_type ORDER BY LINE) LOOP
      IF r.TEXT IS NOT NULL THEN DBMS_LOB.WRITEAPPEND(v_result, LENGTH(r.TEXT), r.TEXT); END IF;
    END LOOP;
    RETURN v_result;
  END;

  FUNCTION checked(p_schema VARCHAR2, p_table VARCHAR2) RETURN {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE IS
    r {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE; v_owner VARCHAR2(128);
  BEGIN
    SELECT * INTO r FROM {{OWNER}}."DBC_MH_TARGETS" WHERE SCHEMA_NAME = p_schema AND TABLE_NAME = p_table;
    EXECUTE IMMEDIATE 'SELECT TRIGGER_OWNER FROM ' || q(p_schema) || '."DBC_METADATA_TRACKING" WHERE TABLE_NAME=:t'
      INTO v_owner USING p_table;
    IF v_owner <> $$PLSQL_UNIT_OWNER OR v_owner IS NULL THEN
      RAISE_APPLICATION_ERROR(-20085, 'History trigger owner changed');
    END IF;
    RETURN r;
  END;

  FUNCTION healthy(r {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE) RETURN BOOLEAN IS
    v_count NUMBER;
    FUNCTION same_source(p_owner VARCHAR2, p_name VARCHAR2, p_type VARCHAR2, p_expected CLOB) RETURN BOOLEAN IS
      v_actual CLOB; v_same BOOLEAN;
    BEGIN
      v_actual := source_text(p_owner, p_name, p_type);
      v_same := DBMS_LOB.GETLENGTH(v_actual) > 0 AND DBMS_LOB.COMPARE(v_actual, p_expected) = 0;
      DBMS_LOB.FREETEMPORARY(v_actual);
      RETURN v_same;
    END;
  BEGIN
    SELECT COUNT(*) INTO v_count FROM SYS.USER_OBJECTS WHERE OBJECT_TYPE='TRIGGER' AND STATUS='VALID'
      AND OBJECT_NAME IN (r.BEFORE_TRIGGER, r.AFTER_TRIGGER);
    IF v_count <> 2 THEN RETURN FALSE; END IF;
    SELECT COUNT(*) INTO v_count FROM SYS.ALL_OBJECTS WHERE OWNER=r.SCHEMA_NAME AND STATUS='VALID'
      AND ((OBJECT_NAME='DBC_METADATA_AUDIT' AND OBJECT_TYPE IN ('PACKAGE','PACKAGE BODY'))
        OR (OBJECT_NAME IN ('DBC_METADATA_HISTORY','DBC_METADATA_TRACKING') AND OBJECT_TYPE='TABLE'));
    RETURN v_count = 4
      AND same_source($$PLSQL_UNIT_OWNER, r.BEFORE_TRIGGER, 'TRIGGER', r.BEFORE_SOURCE)
      AND same_source($$PLSQL_UNIT_OWNER, r.AFTER_TRIGGER, 'TRIGGER', r.AFTER_SOURCE)
      AND same_source(r.SCHEMA_NAME, 'DBC_METADATA_AUDIT', 'PACKAGE', r.SPEC_SOURCE)
      AND same_source(r.SCHEMA_NAME, 'DBC_METADATA_AUDIT', 'PACKAGE BODY', r.BODY_SOURCE);
  END;

  FUNCTION inspect(p_schema VARCHAR2, p_table VARCHAR2) RETURN CLOB IS
    r {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE;
    v_enabled CHAR(1); v_count NUMBER; v_healthy NUMBER := 0; v_read NUMBER := 0; v_manage NUMBER := 0; v_result CLOB;
  BEGIN
    IF permitted(p_schema,p_table,FALSE) THEN v_read := 1; END IF;
    -- An object's owner may inspect its collection status without receiving history-management authority.
    IF v_read=0 AND SYS_CONTEXT('USERENV','SESSION_USER') <> p_schema THEN
      RAISE_APPLICATION_ERROR(-20086, 'History read access denied');
    END IF;
    r := checked(p_schema,p_table);
    IF permitted(p_schema,p_table,TRUE) THEN v_manage := 1; END IF;
    EXECUTE IMMEDIATE 'SELECT ENABLED FROM ' || q(p_schema) || '."DBC_METADATA_TRACKING" WHERE TABLE_NAME=:t'
      INTO v_enabled USING p_table;
    SELECT COUNT(*) INTO v_count FROM SYS.USER_TRIGGERS WHERE TRIGGER_NAME IN (r.BEFORE_TRIGGER,r.AFTER_TRIGGER) AND STATUS='ENABLED';
    IF healthy(r) AND (v_enabled='N' OR v_count=2) THEN v_healthy := 1; END IF;
    SELECT JSON_OBJECT('owner' VALUE $$PLSQL_UNIT_OWNER, 'schema' VALUE p_schema, 'table' VALUE p_table,
      'enabled' VALUE v_enabled, 'healthy' VALUE v_healthy, 'canRead' VALUE v_read, 'canManage' VALUE v_manage,
      'beforeTrigger' VALUE r.BEFORE_TRIGGER, 'afterTrigger' VALUE r.AFTER_TRIGGER RETURNING CLOB) INTO v_result FROM SYS.DUAL;
    RETURN v_result;
  END;

  FUNCTION entries(p_schema VARCHAR2, p_table VARCHAR2, p_page NUMBER) RETURN CLOB IS
    r {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE; v_result CLOB;
  BEGIN
    IF NOT permitted(p_schema,p_table,FALSE) THEN RAISE_APPLICATION_ERROR(-20086, 'History read access denied'); END IF;
    IF p_page IS NULL OR p_page <> TRUNC(p_page) OR p_page < 1 OR p_page > 1000000 THEN
      RAISE_APPLICATION_ERROR(-20085, 'Invalid history page');
    END IF;
    r := checked(p_schema,p_table);
    EXECUTE IMMEDIATE 'SELECT JSON_ARRAYAGG(JSON_OBJECT(' ||
      '''seq'' VALUE TO_CHAR(SEQ), ''eventId'' VALUE RAWTOHEX(EVENT_ID),' ||
      '''changedAt'' VALUE TO_CHAR(CHANGED_AT,''YYYY-MM-DD HH24:MI:SS.FF3 TZH:TZM''),' ||
      '''changedBy'' VALUE CHANGED_BY, ''schema'' VALUE SCHEMA_NAME, ''table'' VALUE TABLE_NAME,' ||
      '''column'' VALUE COLUMN_NAME, ''kind'' VALUE CHANGE_KIND, ''annotationName'' VALUE ANNOTATION_NAME,' ||
      '''beforeJson'' VALUE BEFORE_JSON, ''afterJson'' VALUE AFTER_JSON RETURNING CLOB) ORDER BY SEQ DESC RETURNING CLOB)' ||
      ' FROM (SELECT * FROM ' || q(p_schema) || '."DBC_METADATA_HISTORY" WHERE SCHEMA_NAME=:s AND TABLE_NAME=:t' ||
      ' ORDER BY SEQ DESC OFFSET :n ROWS FETCH NEXT 11 ROWS ONLY)'
      INTO v_result USING p_schema,p_table,(p_page-1)*10;
    RETURN NVL(v_result, TO_CLOB('[]'));
  END;

  PROCEDURE set_enabled(p_schema VARCHAR2, p_table VARCHAR2, p_enabled VARCHAR2) IS
    r {{OWNER}}."DBC_MH_TARGETS"%ROWTYPE;
  BEGIN
    IF p_enabled IS NULL OR p_enabled NOT IN ('Y','N') THEN RAISE_APPLICATION_ERROR(-20085, 'Invalid history switch'); END IF;
    IF NOT permitted(p_schema,p_table,TRUE) THEN RAISE_APPLICATION_ERROR(-20086, 'History management access denied'); END IF;
    r := checked(p_schema,p_table);
    IF NOT healthy(r) THEN RAISE_APPLICATION_ERROR(-20085, 'History code changed or invalid; administrator review required'); END IF;
    -- DDL commits: leave collection OFF if a later stage fails; never claim atomic rollback.
    EXECUTE IMMEDIATE 'UPDATE ' || q(p_schema) || '."DBC_METADATA_TRACKING" SET ENABLED=''N'', CHANGED_AT=SYSTIMESTAMP,' ||
      ' CHANGED_BY=SYS_CONTEXT(''USERENV'',''SESSION_USER'') WHERE TABLE_NAME=:t AND TRIGGER_OWNER=:o'
      USING p_table,$$PLSQL_UNIT_OWNER;
    IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20085,'History configuration changed'); END IF;
    EXECUTE IMMEDIATE 'ALTER TRIGGER ' || q($$PLSQL_UNIT_OWNER) || '.' || q(r.BEFORE_TRIGGER) || CASE WHEN p_enabled='Y' THEN ' ENABLE' ELSE ' DISABLE' END;
    EXECUTE IMMEDIATE 'ALTER TRIGGER ' || q($$PLSQL_UNIT_OWNER) || '.' || q(r.AFTER_TRIGGER) || CASE WHEN p_enabled='Y' THEN ' ENABLE' ELSE ' DISABLE' END;
    IF p_enabled='Y' THEN
      IF NOT permitted(p_schema,p_table,TRUE) THEN RAISE_APPLICATION_ERROR(-20086,'History management access revoked'); END IF;
      EXECUTE IMMEDIATE 'UPDATE ' || q(p_schema) || '."DBC_METADATA_TRACKING" SET ENABLED=''Y'', CHANGED_AT=SYSTIMESTAMP,' ||
        ' CHANGED_BY=SYS_CONTEXT(''USERENV'',''SESSION_USER'') WHERE TABLE_NAME=:t AND TRIGGER_OWNER=:o'
        USING p_table,$$PLSQL_UNIT_OWNER;
      IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20085,'History configuration changed'); END IF;
    END IF;
  END;
END;
