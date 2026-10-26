-- Install only when DBC_SEMANTIC_QUERY does not exist. Never replaces another object.
-- {{QUERY_PROFILE}} is an installer-validated profile name, NOT user input.
CREATE FUNCTION DBC_SEMANTIC_QUERY(
  p_question CLOB,
  p_use_ontology NUMBER DEFAULT 0,
  p_tables_json CLOB DEFAULT '[]'
) RETURN CLOB AUTHID CURRENT_USER IS
  raw_response CLOB;
  src JSON_OBJECT_T;
  out_json JSON_OBJECT_T := JSON_OBJECT_T();
  evidence JSON_OBJECT_T := JSON_OBJECT_T();
  terms JSON_ARRAY_T := JSON_ARRAY_T();
  ontologies JSON_ARRAY_T := JSON_ARRAY_T();
  warnings JSON_ARRAY_T := JSON_ARRAY_T();
  entries JSON_ARRAY_T;
  entry_json JSON_OBJECT_T;
  brief JSON_OBJECT_T;
  tables_json JSON_ARRAY_T;
  phase VARCHAR2(40) := 'validate_input';
  saved_conversation VARCHAR2(128);
  conversation_suspended BOOLEAN := FALSE;
  failure_code NUMBER;
  failure_message VARCHAR2(4000);
  failure_backtrace VARCHAR2(4000);
  -- Same native SELECT serializer as DBC_AI_QUERY, including numeric 100/101.
  PROCEDURE require_size(p_value CLOB,p_max NUMBER) IS
  BEGIN
    IF DBMS_LOB.GETLENGTH(p_value)>p_max THEN
      RAISE_APPLICATION_ERROR(-20067,'Result exceeds size limit');
    END IF;
  END;
  FUNCTION query_rows(p_sql CLOB,p_limit NUMBER) RETURN JSON_OBJECT_T IS
    rc SYS_REFCURSOR; cursor_no INTEGER; count_columns INTEGER; description DBMS_SQL.DESC_TAB2;
    value_text VARCHAR2(32767); value_number NUMBER; value_date DATE; value_time TIMESTAMP;
    value_float BINARY_FLOAT; value_double BINARY_DOUBLE;
    rows_json JSON_ARRAY_T:=JSON_ARRAY_T(); columns_json JSON_ARRAY_T:=JSON_ARRAY_T(); row_json JSON_ARRAY_T;
    result JSON_OBJECT_T:=JSON_OBJECT_T(); more BOOLEAN:=FALSE;
  BEGIN
    -- Native SELECT cursor. Do NOT parse arbitrary model output with DBMS_SQL.PARSE (DDL executes during parse).
    OPEN rc FOR p_sql;
    cursor_no:=DBMS_SQL.TO_CURSOR_NUMBER(rc);
    DBMS_SQL.DESCRIBE_COLUMNS2(cursor_no,count_columns,description);
    IF count_columns>40 THEN RAISE_APPLICATION_ERROR(-20067,'More than 40 result columns'); END IF;
    FOR i IN 1..count_columns LOOP
      columns_json.append(description(i).col_name);
      CASE description(i).col_type
        WHEN 2 THEN DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_number);
        WHEN 100 THEN DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_float);
        WHEN 101 THEN DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_double);
        WHEN 12 THEN DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_date);
        WHEN 180 THEN DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_time);
        ELSE
          IF description(i).col_type NOT IN (1,96) THEN RAISE_APPLICATION_ERROR(-20067,'Unsupported result type: '||description(i).col_type); END IF;
          DBMS_SQL.DEFINE_COLUMN(cursor_no,i,value_text,32767);
      END CASE;
    END LOOP;
    WHILE DBMS_SQL.FETCH_ROWS(cursor_no)>0 LOOP
      IF rows_json.get_size>=p_limit THEN more:=TRUE;EXIT; END IF;
      row_json:=JSON_ARRAY_T();
      FOR i IN 1..count_columns LOOP
        CASE description(i).col_type
          WHEN 2 THEN DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_number);IF value_number IS NULL THEN row_json.append_null; ELSE row_json.append(TO_CHAR(value_number,'TM9','NLS_NUMERIC_CHARACTERS=''.,''')); END IF;
          -- Preserve native binary types: NUMBER conversion can overflow or lose small values.
          -- Keep decimal-string cells; non-finite values fail explicitly, never become zero/NULL.
          WHEN 100 THEN
            DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_float);
            IF value_float IS NULL THEN row_json.append_null;
            ELSIF value_float IS NAN OR value_float IN (BINARY_FLOAT_INFINITY,-BINARY_FLOAT_INFINITY) THEN
              RAISE_APPLICATION_ERROR(-20067,'Non-finite BINARY_FLOAT result at column '||i||' ('||description(i).col_name||'); cannot return a finite numeric result');
            ELSE row_json.append(TO_CHAR(value_float,'TM9','NLS_NUMERIC_CHARACTERS=''.,''')); END IF;
          WHEN 101 THEN
            DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_double);
            IF value_double IS NULL THEN row_json.append_null;
            ELSIF value_double IS NAN OR value_double IN (BINARY_DOUBLE_INFINITY,-BINARY_DOUBLE_INFINITY) THEN
              RAISE_APPLICATION_ERROR(-20067,'Non-finite BINARY_DOUBLE result at column '||i||' ('||description(i).col_name||'); cannot return a finite numeric result');
            ELSE row_json.append(TO_CHAR(value_double,'TM9','NLS_NUMERIC_CHARACTERS=''.,''')); END IF;
          WHEN 12 THEN DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_date);IF value_date IS NULL THEN row_json.append_null; ELSE row_json.append(TO_CHAR(value_date,'YYYY-MM-DD"T"HH24:MI:SS')); END IF;
          WHEN 180 THEN DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_time);IF value_time IS NULL THEN row_json.append_null; ELSE row_json.append(TO_CHAR(value_time,'YYYY-MM-DD"T"HH24:MI:SS.FF9')); END IF;
          ELSE DBMS_SQL.COLUMN_VALUE(cursor_no,i,value_text);IF value_text IS NULL THEN row_json.append_null; ELSE row_json.append(value_text); END IF;
        END CASE;
      END LOOP;
      rows_json.append(row_json);require_size(rows_json.to_clob,1000000);
    END LOOP;
    DBMS_SQL.CLOSE_CURSOR(cursor_no);
    result.put('columns',columns_json);result.put('rows',rows_json);result.put('more',more);RETURN result;
  EXCEPTION WHEN OTHERS THEN
    IF cursor_no IS NOT NULL THEN IF DBMS_SQL.IS_OPEN(cursor_no) THEN DBMS_SQL.CLOSE_CURSOR(cursor_no); END IF;
    ELSIF rc%ISOPEN THEN CLOSE rc; END IF;RAISE;
  END;
  PROCEDURE restore_conversation IS
  BEGIN
    IF conversation_suspended THEN
      DBMS_CLOUD_AI.SET_CONVERSATION_ID(saved_conversation);
      conversation_suspended := FALSE;
    END IF;
  END;
BEGIN
  -- No profile, glossary toggle, SQL text, mode or row limit is exposed to the LLM.
  IF p_question IS NULL OR DBMS_LOB.GETLENGTH(p_question)=0
     OR DBMS_LOB.GETLENGTH(p_question)>8000
     OR p_use_ontology IS NULL OR p_use_ontology NOT IN (0,1) THEN
    RAISE_APPLICATION_ERROR(-20080,'Question (1-8000 characters) and ontology option (0/1) are required.');
  END IF;
  IF p_tables_json IS NULL OR DBMS_LOB.GETLENGTH(p_tables_json)>4000 THEN
    RAISE_APPLICATION_ERROR(-20080,'Ontology table names must be a JSON array under 4000 characters.');
  END IF;
  tables_json := JSON_ARRAY_T.parse(p_tables_json);
  IF p_use_ontology=0 AND tables_json.get_size<>0 THEN
    RAISE_APPLICATION_ERROR(-20080,'Pass [] when ontology is OFF; no table selection is silently ignored.');
  END IF;
  IF p_use_ontology=1 AND (tables_json.get_size<1 OR tables_json.get_size>10) THEN
    RAISE_APPLICATION_ERROR(-20080,'Choose 1 to 10 approved ontology tables when ontology is ON.');
  END IF;

  phase := 'dictionary_ontology_generate_sql';
  -- The outer Agent owns a conversation, but the existing standalone query
  -- package deliberately requires an isolated generation. Suspend only the
  -- session binding (do not delete conversation/history) and restore on BOTH
  -- success and failure. Existing DBC_AI_QUERY is not modified.
  saved_conversation := DBMS_CLOUD_AI.GET_CONVERSATION_ID;
  IF saved_conversation IS NOT NULL THEN
    DBMS_CLOUD_AI.CLEAR_CONVERSATION_ID;
    conversation_suspended := TRUE;
  END IF;
  raw_response := DBC_AI_QUERY.ASK(
    p_question => p_question,
    p_profile => '{{QUERY_PROFILE}}',
    p_use_glossary => 1,
    p_use_ontology => p_use_ontology,
    p_tables => p_tables_json,
    p_mode => 'SQL',
    p_max_rows => 1000);
  restore_conversation;

  -- Preserve the exact generated SQL BEFORE executing it. No regeneration,
  -- silent SQL repair or extra AI call is involved in the execution phase.
  src := JSON_OBJECT_T.parse(raw_response);
  brief := JSON_OBJECT_T();
  brief.put('status','GENERATED');
  brief.put('sql',src.get('sql'));
  brief.put('question',src.get('question'));
  brief.put('profile',src.get('profile'));
  BEGIN
    DBC_AGENT_RESULT_STORE.save_result(brief.to_clob);
  EXCEPTION WHEN OTHERS THEN NULL; END;
  phase := 'execute_generated_sql';
  DECLARE
    started NUMBER:=DBMS_UTILITY.GET_TIME;
    timing_json JSON_OBJECT_T:=src.get_object('timing');
  BEGIN
    src.put('result',query_rows(src.get_clob('sql'),1000));
    timing_json.put('queryMs',(DBMS_UTILITY.GET_TIME-started)*10);
    timing_json.put('totalMs',timing_json.get_number('totalMs')+(DBMS_UTILITY.GET_TIME-started)*10);
    src.put('timing',timing_json);
  END;

  phase := 'format_response';
  -- Keep actual result cells and SQL unchanged; do not send the duplicated full
  -- prompt to the orchestration model. Trace the evidence identities/versions.
  entries := src.get_object('glossary').get_array('definitions');
  FOR i IN 0..entries.get_size-1 LOOP
    entry_json := TREAT(entries.get(i) AS JSON_OBJECT_T);
    brief := JSON_OBJECT_T();
    brief.put('id',entry_json.get('id'));
    brief.put('term',entry_json.get('term'));
    brief.put('revision',entry_json.get('revision'));
    terms.append(brief);
  END LOOP;
  entries := src.get_array('ontology');
  FOR i IN 0..entries.get_size-1 LOOP
    entry_json := TREAT(entries.get(i) AS JSON_OBJECT_T);
    brief := JSON_OBJECT_T();
    brief.put('schema',entry_json.get('schema'));
    brief.put('table',entry_json.get('table'));
    brief.put('documentId',entry_json.get('documentId'));
    brief.put('revision',entry_json.get('revision'));
    ontologies.append(brief);
  END LOOP;
  IF terms.get_size=0 THEN warnings.append('NO_MATCHED_BUSINESS_TERMS'); END IF;
  IF src.get_object('result').get_boolean('more') THEN
    warnings.append('RESULT_TRUNCATED_DO_NOT_INFER_TOTALS_OR_COMPLETE_CHARTS');
  END IF;
  evidence.put('glossaryRequired',TRUE);
  evidence.put('terms',terms);
  evidence.put('ontologyEnabled',p_use_ontology=1);
  evidence.put('ontology',ontologies);
  out_json.put('status','SUCCESS');
  out_json.put('question',src.get('question'));
  out_json.put('actor',src.get('actor'));
  out_json.put('profile',src.get('profile'));
  out_json.put('evidence',evidence);
  out_json.put('sql',src.get('sql'));
  out_json.put('result',src.get('result'));
  out_json.put('numberEncoding','decimal strings; null remains JSON null');
  out_json.put('timing',src.get('timing'));
  out_json.put('warnings',warnings);
  -- Commit only recovery data independently, before the final model call.
  -- A recovery failure must not turn a completed SQL query into a false error.
  BEGIN
    DBC_AGENT_RESULT_STORE.save_result(out_json.to_clob);
    out_json.put('recovery','SAVED');
  EXCEPTION WHEN OTHERS THEN
    out_json.put('recovery','UNAVAILABLE');
    warnings.append('RESULT_RECOVERY_UNAVAILABLE');
    out_json.put('warnings',warnings);
  END;
  RETURN DBC_AGENT_RESULT_STORE.model_preview(out_json.to_clob);
EXCEPTION WHEN OTHERS THEN
  failure_code := SQLCODE;
  failure_message := SUBSTR(SQLERRM,1,2000);
  failure_backtrace := SUBSTR(DBMS_UTILITY.FORMAT_ERROR_BACKTRACE,1,4000);
  BEGIN
    restore_conversation;
  EXCEPTION WHEN OTHERS THEN
    failure_message := failure_message || ' Conversation restore failed: ' || SUBSTR(SQLERRM,1,1000);
  END;
  out_json := JSON_OBJECT_T();
  out_json.put('status','ERROR');
  out_json.put('phase',phase);
  out_json.put('oracleCode',failure_code);
  out_json.put('message',failure_message);
  out_json.put('retryable',FALSE);
  out_json.put('backtrace',failure_backtrace);
  BEGIN
    -- Keep failing SQL in authenticated storage, not in the model preview.
    brief := JSON_OBJECT_T.parse(out_json.to_clob);
    IF src IS NOT NULL AND src.has('sql') THEN brief.put('sql',src.get('sql')); END IF;
    DBC_AGENT_RESULT_STORE.save_result(brief.to_clob);
  EXCEPTION WHEN OTHERS THEN NULL; -- Preserve the original error, never success.
  END;
  -- No fallback to an ungrounded query, no 0/empty-success substitution.
  RETURN out_json.to_clob;
END;
