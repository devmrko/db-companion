-- Install once. No grants or changes to business data. Result access expires
-- after seven days; expired records are physically purged on the next request.
CREATE TABLE DBC_AGENT_RESULTS (
  request_id VARCHAR2(32) PRIMARY KEY,
  db_actor VARCHAR2(128) NOT NULL,
  app_id VARCHAR2(30) NOT NULL,
  app_session VARCHAR2(40) NOT NULL,
  app_actor VARCHAR2(255) NOT NULL,
  conversation_id VARCHAR2(256) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  payload CLOB CHECK (payload IS JSON),
  saved_at TIMESTAMP WITH TIME ZONE
);
/
CREATE PACKAGE DBC_AGENT_RESULT_STORE AUTHID CURRENT_USER AS
  PROCEDURE begin_request(p_request VARCHAR2, p_conversation VARCHAR2);
  PROCEDURE end_request;
  PROCEDURE save_result(p_payload CLOB);
  FUNCTION read_result(p_request VARCHAR2, p_conversation VARCHAR2) RETURN CLOB;
  FUNCTION model_preview(p_payload CLOB) RETURN CLOB;
END;
/
CREATE PACKAGE BODY DBC_AGENT_RESULT_STORE AS
  g_request VARCHAR2(32);
  g_conversation VARCHAR2(256);
  g_session VARCHAR2(40);
  g_actor VARCHAR2(255);
  PROCEDURE end_request IS BEGIN
    g_request:=NULL; g_conversation:=NULL; g_session:=NULL; g_actor:=NULL;
  END;
  PROCEDURE begin_request(p_request VARCHAR2, p_conversation VARCHAR2) IS
    PRAGMA AUTONOMOUS_TRANSACTION;
  BEGIN
    end_request;
    IF NOT REGEXP_LIKE(p_request,'^[a-f0-9]{32}$','c') OR p_request IS NULL
       OR p_conversation IS NULL OR LENGTH(p_conversation)>256
       OR SYS_CONTEXT('APEX$SESSION','APP_SESSION') IS NULL
       OR SYS_CONTEXT('APEX$SESSION','APP_USER') IS NULL
       OR SYS_CONTEXT('APEX$SESSION','APP_USER') IN ('nobody','APEX_PUBLIC_USER') THEN
      RAISE_APPLICATION_ERROR(-20081,'Authenticated request context is required.');
    END IF;
    DELETE FROM DBC_AGENT_RESULTS WHERE expires_at<=SYSTIMESTAMP;
    INSERT INTO DBC_AGENT_RESULTS(request_id,db_actor,app_id,app_session,app_actor,conversation_id,expires_at)
    VALUES(p_request,SYS_CONTEXT('USERENV','SESSION_USER'),v('APP_ID'),
      SYS_CONTEXT('APEX$SESSION','APP_SESSION'),SYS_CONTEXT('APEX$SESSION','APP_USER'),p_conversation,
      SYSTIMESTAMP+INTERVAL '7' DAY);
    COMMIT;
    g_request:=p_request; g_conversation:=p_conversation;
    g_session:=SYS_CONTEXT('APEX$SESSION','APP_SESSION');
    g_actor:=SYS_CONTEXT('APEX$SESSION','APP_USER');
  EXCEPTION WHEN OTHERS THEN ROLLBACK; end_request; RAISE;
  END;
  PROCEDURE save_result(p_payload CLOB) IS
    PRAGMA AUTONOMOUS_TRANSACTION;
  BEGIN
    IF g_request IS NULL OR g_session IS NULL OR g_actor IS NULL
       OR NVL(SYS_CONTEXT('APEX$SESSION','APP_SESSION'),'!')<>g_session
       OR NVL(SYS_CONTEXT('APEX$SESSION','APP_USER'),'!')<>g_actor THEN
      RAISE_APPLICATION_ERROR(-20081,'No authenticated result recovery context.');
    END IF;
    IF DBMS_LOB.GETLENGTH(p_payload)>8000000 THEN
      RAISE_APPLICATION_ERROR(-20081,'Saved result exceeds size limit.');
    END IF;
    UPDATE DBC_AGENT_RESULTS SET payload=p_payload,saved_at=SYSTIMESTAMP
    WHERE request_id=g_request AND conversation_id=g_conversation
      AND db_actor=SYS_CONTEXT('USERENV','SESSION_USER') AND app_id=v('APP_ID')
      AND app_session=g_session AND app_actor=g_actor
      AND expires_at>SYSTIMESTAMP
      AND (payload IS NULL OR JSON_VALUE(payload,'$.status')='GENERATED');
    IF SQL%ROWCOUNT<>1 THEN RAISE_APPLICATION_ERROR(-20081,'Result already saved or request expired.'); END IF;
    COMMIT;
  EXCEPTION WHEN OTHERS THEN ROLLBACK; RAISE;
  END;
  FUNCTION read_result(p_request VARCHAR2,p_conversation VARCHAR2) RETURN CLOB IS
    l_payload CLOB;
  BEGIN
    SELECT payload INTO l_payload FROM (
    SELECT payload FROM DBC_AGENT_RESULTS
    WHERE (p_request IS NULL OR request_id=p_request) AND conversation_id=p_conversation
      AND db_actor=SYS_CONTEXT('USERENV','SESSION_USER') AND app_id=v('APP_ID')
      AND app_session=SYS_CONTEXT('APEX$SESSION','APP_SESSION')
      AND app_actor=SYS_CONTEXT('APEX$SESSION','APP_USER') AND expires_at>SYSTIMESTAMP
    ORDER BY created_at DESC) WHERE ROWNUM=1;
    IF l_payload IS NULL THEN RETURN TO_CLOB('{"status":"PENDING"}'); END IF;
    RETURN l_payload;
  EXCEPTION WHEN NO_DATA_FOUND THEN RETURN TO_CLOB('{"status":"UNAVAILABLE"}');
  END;
  FUNCTION model_preview(p_payload CLOB) RETURN CLOB IS
    l_doc JSON_OBJECT_T:=JSON_OBJECT_T.parse(p_payload);
    l_result JSON_OBJECT_T;
    l_rows JSON_ARRAY_T;
    l_preview JSON_ARRAY_T:=JSON_ARRAY_T();
    l_warnings JSON_ARRAY_T;
    l_row CLOB;
    l_budget PLS_INTEGER:=0;
  BEGIN
    IF l_doc.get_string('status')<>'SUCCESS' THEN RETURN p_payload; END IF;
    l_result:=l_doc.get_object('result'); l_rows:=l_result.get_array('rows');
    FOR i IN 0..LEAST(l_rows.get_size,5)-1 LOOP
      l_row:=l_rows.get(i).to_clob;
      EXIT WHEN DBMS_LOB.GETLENGTH(l_row)+l_budget>2000;
      l_preview.append(l_rows.get(i)); l_budget:=l_budget+DBMS_LOB.GETLENGTH(l_row);
    END LOOP;
    l_result.put('fetchedRowCount',l_rows.get_size);
    l_result.put('previewRowCount',l_preview.get_size);
    l_result.put('previewTruncated',l_preview.get_size<l_rows.get_size);
    l_result.put('rows',l_preview); l_doc.put('result',l_result);
    -- Full SQL is kept in recovery storage, not duplicated into LLM context.
    l_doc.remove('sql'); l_doc.remove('question');
    l_warnings:=l_doc.get_array('warnings');
    IF l_preview.get_size<l_rows.get_size THEN l_warnings.append('PREVIEW_ONLY_DO_NOT_SUM_OR_INFER_TOTALS'); END IF;
    l_doc.put('warnings',l_warnings);
    l_doc.put('responseInstruction','Answer briefly (at most 5 rows). Stored query result and SQL are displayed by the client. Never infer totals from this preview. Do not retry.');
    -- Pathological column names/evidence also cannot create an unbounded prompt.
    IF DBMS_LOB.GETLENGTH(l_doc.to_clob)>8000 THEN
      l_doc.remove('evidence'); l_result.remove('columns');
      l_result.put('rows',JSON_ARRAY_T()); l_result.put('previewRowCount',0);
      l_result.put('previewTruncated',l_rows.get_size>0); l_doc.put('result',l_result);
      l_doc.put('responseInstruction','Query completed; use the saved-result panel. Preview omitted due to size. Do not invent values or call again.');
    END IF;
    RETURN l_doc.to_clob;
  END;
END;
/
