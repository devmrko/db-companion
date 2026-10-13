  FUNCTION local_object(p_name VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN DBMS_ASSERT.ENQUOTE_NAME(SYS_CONTEXT('USERENV','CURRENT_USER'),FALSE)
      ||'.'||DBMS_ASSERT.ENQUOTE_NAME(p_name,FALSE);
  END;

  PROCEDURE require_size(p_value CLOB,p_limit NUMBER) IS
  BEGIN
    IF DBMS_LOB.GETLENGTH(p_value)>p_limit THEN
      RAISE_APPLICATION_ERROR(-20060,'Evidence/result limit exceeded; narrow the scope. Nothing is silently truncated.');
    END IF;
  END;

  FUNCTION glossary(p_question VARCHAR2) RETURN JSON_OBJECT_T IS
    ts CTX_DOC.TOKEN_TAB; n BINARY_INTEGER; words JSON_OBJECT_T:=JSON_OBJECT_T();
    tokens JSON_ARRAY_T:=JSON_ARRAY_T(); definitions JSON_ARRAY_T:=JSON_ARRAY_T();
    result JSON_OBJECT_T:=JSON_OBJECT_T(); term JSON_OBJECT_T; target JSON_OBJECT_T;
    query_text VARCHAR2(32767); block_text CLOB; matches CLOB; targets JSON_ARRAY_T;
    ids JSON_OBJECT_T:=JSON_OBJECT_T(); names JSON_KEY_LIST;
    cur SYS_REFCURSOR; term_id VARCHAR2(36); rev NUMBER; label VARCHAR2(256 CHAR);
    aliases CLOB; definition CLOB; criteria CLOB; table_check NUMBER;
  BEGIN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM '||local_object('DBC_BUSINESS_TERM')||' WHERE 1=0' INTO table_check;
    CTX_DOC.POLICY_TOKENS(policy_name=>'DBC_BT_KO_POLICY',document=>p_question,restab=>ts,format=>'TEXT');
    IF ts.COUNT>256 THEN RAISE_APPLICATION_ERROR(-20061,'Token limit exceeded'); END IF;
    n:=ts.FIRST;
    WHILE n IS NOT NULL LOOP
      IF ts(n).token IS NOT NULL AND NOT words.has(ts(n).token) THEN
        IF LENGTH(ts(n).token)>256 OR words.get_size>=64 THEN RAISE_APPLICATION_ERROR(-20061,'Token limit exceeded'); END IF;
        words.put(ts(n).token,TRUE); tokens.append(ts(n).token);
        IF query_text IS NOT NULL THEN query_text:=query_text||' OR '; END IF;
        query_text:=query_text||'{'||REPLACE(REPLACE(ts(n).token,'\','\\'),'}','\}')||'}';
      END IF;
      n:=ts.NEXT(n);
    END LOOP;
    targets:=JSON_ARRAY_T();
    IF query_text IS NOT NULL THEN
      block_text:=REPLACE(TO_CLOB('{{PHRASE_BLOCK}}'),'{{GLOSSARY_TABLE}}',local_object('DBC_BUSINESS_TERM'));
      EXECUTE IMMEDIATE block_text USING IN p_question,IN query_text,OUT matches;
      targets:=JSON_ARRAY_T.parse(matches);
      FOR i IN 0..targets.get_size-1 LOOP
        target:=TREAT(targets.get(i) AS JSON_OBJECT_T);
        DECLARE found JSON_ARRAY_T:=target.get_array('termIds'); BEGIN
          FOR j IN 0..found.get_size-1 LOOP ids.put(found.get_string(j),TRUE); END LOOP;
        END;
      END LOOP;
      IF ids.get_size>30 THEN RAISE_APPLICATION_ERROR(-20062,'More than 30 matched definitions; narrow the question'); END IF;
      names:=ids.get_keys;
      FOR i IN 1..names.COUNT LOOP
        OPEN cur FOR 'SELECT TERM_ID,REVISION,TERM_TEXT,ALIASES_JSON,DEFINITION_TEXT,SQL_CRITERIA FROM '
          ||local_object('DBC_BUSINESS_TERM')||' WHERE TERM_ID=:id AND ENABLED_YN=''Y''' USING names(i);
        FETCH cur INTO term_id,rev,label,aliases,definition,criteria;
        IF cur%NOTFOUND THEN CLOSE cur; RAISE_APPLICATION_ERROR(-20063,'Definition changed during lookup; retry explicitly'); END IF;
        CLOSE cur;
        term:=JSON_OBJECT_T();term.put('id',term_id);term.put('revision',rev);term.put('term',label);
        term.put('aliases',JSON_ARRAY_T.parse(aliases));term.put('definition',definition);term.put('criteria',criteria);
        definitions.append(term);
      END LOOP;
    END IF;
    result.put('tokens',tokens);result.put('textQuery',query_text);result.put('targets',targets);result.put('definitions',definitions);
    require_size(result.to_clob,40000);
    RETURN result;
  EXCEPTION WHEN OTHERS THEN
    IF cur%ISOPEN THEN CLOSE cur; END IF;
    RAISE;
  END;
