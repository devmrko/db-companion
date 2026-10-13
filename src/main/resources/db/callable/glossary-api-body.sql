CREATE PACKAGE BODY {{API}} AS
  -- DB Companion glossary API v2
  FUNCTION dictionary_table RETURN VARCHAR2 IS
  BEGIN
    RETURN DBMS_ASSERT.ENQUOTE_NAME(SYS_CONTEXT('USERENV','CURRENT_USER'),FALSE)
      ||'."DBC_BUSINESS_TERM"';
  END;

  FUNCTION validate_question(p_question CLOB) RETURN VARCHAR2 IS
    question_text VARCHAR2(32767);
  BEGIN
    IF p_question IS NULL OR DBMS_LOB.GETLENGTH(p_question)>8000 THEN
      RAISE_APPLICATION_ERROR(-20060,'Question must contain 1 to 8000 characters');
    END IF;
    question_text:=TRIM(DBMS_LOB.SUBSTR(p_question,8000,1));
    IF question_text IS NULL THEN
      RAISE_APPLICATION_ERROR(-20060,'Question must not be blank');
    END IF;
    RETURN question_text;
  END;

  PROCEDURE require_dictionary IS
    ignored_count NUMBER;
  BEGIN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM '||dictionary_table||' WHERE 1=0'
      INTO ignored_count;
  END;

  FUNCTION tokenize_question(p_question VARCHAR2) RETURN JSON_ARRAY_T IS
    native_tokens CTX_DOC.TOKEN_TAB;
    unique_words JSON_OBJECT_T:=JSON_OBJECT_T();
    tokens JSON_ARRAY_T:=JSON_ARRAY_T();
    position_no BINARY_INTEGER;
    word_text VARCHAR2(32767);
  BEGIN
    CTX_DOC.POLICY_TOKENS(
      policy_name=>'DBC_BT_KO_POLICY',document=>p_question,
      restab=>native_tokens,format=>'TEXT');
    IF native_tokens.COUNT>256 THEN
      RAISE_APPLICATION_ERROR(-20061,'Token limit exceeded');
    END IF;
    position_no:=native_tokens.FIRST;
    WHILE position_no IS NOT NULL LOOP
      word_text:=native_tokens(position_no).token;
      IF word_text IS NOT NULL AND NOT unique_words.has(word_text) THEN
        IF LENGTH(word_text)>256 OR unique_words.get_size>=64 THEN
          RAISE_APPLICATION_ERROR(-20061,'Token limit exceeded');
        END IF;
        unique_words.put(word_text,TRUE);
        tokens.append(word_text);
      END IF;
      position_no:=native_tokens.NEXT(position_no);
    END LOOP;
    RETURN tokens;
  END;

  FUNCTION escape_text_phrase(p_phrase VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN '{'||REPLACE(REPLACE(p_phrase,'\','\\'),'}','\}')||'}';
  END;

  FUNCTION build_text_query(p_tokens JSON_ARRAY_T) RETURN VARCHAR2 IS
    query_text VARCHAR2(32767);
  BEGIN
    FOR i IN 0..p_tokens.get_size-1 LOOP
      IF query_text IS NOT NULL THEN query_text:=query_text||' OR '; END IF;
      query_text:=query_text||escape_text_phrase(p_tokens.get_string(i));
    END LOOP;
    RETURN query_text;
  END;

  FUNCTION find_term_candidates(p_query VARCHAR2) RETURN SYS_REFCURSOR IS
    candidates SYS_REFCURSOR;
    active_count NUMBER;
    sql_text VARCHAR2(32767);
  BEGIN
    IF p_query IS NULL THEN
      OPEN candidates FOR
        SELECT CAST(NULL AS VARCHAR2(36)),CAST(NULL AS VARCHAR2(256 CHAR)),
               CAST(NULL AS VARCHAR2(10)) FROM DUAL WHERE 1=0;
      RETURN candidates;
    END IF;
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM '||dictionary_table||q'~ WHERE ENABLED_YN='Y'~'
      INTO active_count;
    IF active_count>2000 THEN
      RAISE_APPLICATION_ERROR(-20001,'Active dictionary term limit exceeded');
    END IF;
    -- Only the owner-local table identifier is dynamic. Search text is bound.
    sql_text:='WITH candidates AS (SELECT TERM_ID,TERM_TEXT,ALIASES_JSON FROM '
      ||dictionary_table||q'~
        WHERE ENABLED_YN='Y' AND CONTAINS(SEARCH_TEXT,:candidate_query,1)>0
      ), labels AS (
        SELECT TERM_ID,TERM_TEXT AS LABEL,'TERM' AS KIND FROM candidates
        UNION ALL
        SELECT t.TERM_ID,a.LABEL,'ALIAS' AS KIND FROM candidates t,
          JSON_TABLE(t.ALIASES_JSON,'$[*]'
            COLUMNS(LABEL VARCHAR2(256 CHAR) PATH '$' ERROR ON ERROR)) a
      )
      SELECT DISTINCT TERM_ID,LABEL,KIND FROM labels~';
    OPEN candidates FOR sql_text USING p_query;
    RETURN candidates;
  END;

  FUNCTION match_question_terms(p_question VARCHAR2,p_candidates SYS_REFCURSOR)
    RETURN JSON_ARRAY_T IS
    hits JSON_ARRAY_T:=JSON_ARRAY_T();
    spans CTX_DOC.HIGHLIGHT_TAB;
    position_no BINARY_INTEGER;
    term_id VARCHAR2(36);
    label_text VARCHAR2(256 CHAR);
    match_kind VARCHAR2(10);
    item JSON_OBJECT_T;
  BEGIN
    LOOP
      FETCH p_candidates INTO term_id,label_text,match_kind;
      EXIT WHEN p_candidates%NOTFOUND;
      CTX_DOC.POLICY_HIGHLIGHT(
        policy_name=>'DBC_BT_KO_POLICY',document=>p_question,
        text_query=>escape_text_phrase(label_text),
        restab=>spans,plaintext=>TRUE,format=>'TEXT');
      position_no:=spans.FIRST;
      WHILE position_no IS NOT NULL LOOP
        item:=JSON_OBJECT_T();
        item.put('termId',term_id);
        item.put('label',label_text);
        item.put('kind',match_kind);
        item.put('start',spans(position_no).offset);
        item.put('end',spans(position_no).offset+spans(position_no).length);
        -- Oracle Text offsets use UCS2, including positions after emoji.
        item.put('matched',SUBSTR2(p_question,spans(position_no).offset,spans(position_no).length));
        hits.append(item);
        IF hits.get_size>512 THEN
          RAISE_APPLICATION_ERROR(-20001,'Dictionary phrase occurrence limit exceeded');
        END IF;
        position_no:=spans.NEXT(position_no);
      END LOOP;
    END LOOP;
    CLOSE p_candidates;
    RETURN hits;
  EXCEPTION WHEN OTHERS THEN
    IF p_candidates%ISOPEN THEN CLOSE p_candidates; END IF;
    RAISE;
  END;

  FUNCTION remove_overlapping_matches(p_hits JSON_ARRAY_T) RETURN JSON_ARRAY_T IS
    occurrences CLOB:=p_hits.to_clob();
    targets JSON_ARRAY_T:=JSON_ARRAY_T();
    item JSON_OBJECT_T;
  BEGIN
    FOR r IN (
      WITH matched AS (
        SELECT j.*,
               UPPER(REGEXP_REPLACE(TRIM(j.LABEL),'[[:space:]]+',' ')) AS EXPRESSION
        FROM JSON_TABLE(occurrences,'$[*]' COLUMNS(
          TERM_ID VARCHAR2(36) PATH '$.termId',LABEL VARCHAR2(256 CHAR) PATH '$.label',
          KIND VARCHAR2(10) PATH '$.kind',START_POS NUMBER PATH '$.start',END_POS NUMBER PATH '$.end',
          MATCHED_TEXT VARCHAR2(4000 CHAR) PATH '$.matched')) j
        -- Keep morphology, but do not join phrases across extra punctuation.
        WHERE NVL(REGEXP_REPLACE(j.LABEL,'[[:alnum:][:space:]]',''),' ')=
              NVL(REGEXP_REPLACE(j.MATCHED_TEXT,'[[:alnum:][:space:]]',''),' ')
      ), retained AS (
        SELECT m.* FROM matched m
        WHERE NOT EXISTS (
          SELECT 1 FROM matched longer
          WHERE longer.START_POS<=m.START_POS AND longer.END_POS>=m.END_POS
            AND longer.END_POS-longer.START_POS>m.END_POS-m.START_POS
        )
      ), unique_terms AS (
        SELECT EXPRESSION,TERM_ID,MAX(CASE WHEN KIND='TERM' THEN 1 ELSE 0 END) AS IS_TERM
        FROM retained GROUP BY EXPRESSION,TERM_ID
      )
      SELECT EXPRESSION,CASE WHEN MAX(IS_TERM)=1 THEN 'TERM' ELSE 'ALIAS' END AS KIND,
             JSON_ARRAYAGG(TERM_ID ORDER BY TERM_ID RETURNING CLOB) AS TERM_IDS
      FROM unique_terms GROUP BY EXPRESSION
      ORDER BY LENGTH(EXPRESSION) DESC,EXPRESSION
    ) LOOP
      item:=JSON_OBJECT_T();
      item.put('expression',r.EXPRESSION);
      item.put('kind',r.KIND);
      item.put('termIds',JSON_ARRAY_T.parse(r.TERM_IDS));
      targets.append(item);
    END LOOP;
    DBMS_LOB.FREETEMPORARY(occurrences);
    RETURN targets;
  EXCEPTION WHEN OTHERS THEN
    IF DBMS_LOB.ISTEMPORARY(occurrences)=1 THEN DBMS_LOB.FREETEMPORARY(occurrences); END IF;
    RAISE;
  END;

  FUNCTION load_term_definitions(p_matches JSON_ARRAY_T) RETURN JSON_ARRAY_T IS
    ids JSON_OBJECT_T:=JSON_OBJECT_T();
    names JSON_KEY_LIST;
    target JSON_OBJECT_T;
    found JSON_ARRAY_T;
    definitions JSON_ARRAY_T:=JSON_ARRAY_T();
    term JSON_OBJECT_T;
    term_cursor SYS_REFCURSOR;
    term_id VARCHAR2(36);
    revision_no NUMBER;
    label_text VARCHAR2(256 CHAR);
    aliases CLOB;
    definition CLOB;
    criteria CLOB;
  BEGIN
    FOR i IN 0..p_matches.get_size-1 LOOP
      target:=TREAT(p_matches.get(i) AS JSON_OBJECT_T);
      found:=target.get_array('termIds');
      FOR j IN 0..found.get_size-1 LOOP ids.put(found.get_string(j),TRUE); END LOOP;
    END LOOP;
    IF ids.get_size>30 THEN
      RAISE_APPLICATION_ERROR(-20062,'More than 30 matched definitions; narrow the question');
    END IF;
    names:=ids.get_keys;
    FOR i IN 1..names.COUNT LOOP
      OPEN term_cursor FOR
        'SELECT TERM_ID,REVISION,TERM_TEXT,ALIASES_JSON,DEFINITION_TEXT,SQL_CRITERIA FROM '
        ||dictionary_table||q'~ WHERE TERM_ID=:term_id AND ENABLED_YN='Y'~'
        USING names(i);
      FETCH term_cursor INTO term_id,revision_no,label_text,aliases,definition,criteria;
      IF term_cursor%NOTFOUND THEN
        CLOSE term_cursor;
        RAISE_APPLICATION_ERROR(-20063,'Definition changed during lookup; retry explicitly');
      END IF;
      CLOSE term_cursor;
      term:=JSON_OBJECT_T();
      term.put('id',term_id);
      term.put('revision',revision_no);
      term.put('term',label_text);
      term.put('aliases',JSON_ARRAY_T.parse(aliases));
      term.put('definition',definition);
      term.put('criteria',criteria);
      definitions.append(term);
    END LOOP;
    RETURN definitions;
  EXCEPTION WHEN OTHERS THEN
    IF term_cursor%ISOPEN THEN CLOSE term_cursor; END IF;
    RAISE;
  END;

  FUNCTION build_search_response(p_question VARCHAR2,p_tokens JSON_ARRAY_T,
    p_query VARCHAR2,p_matches JSON_ARRAY_T,p_definitions JSON_ARRAY_T) RETURN CLOB IS
    glossary JSON_OBJECT_T:=JSON_OBJECT_T();
    response JSON_OBJECT_T:=JSON_OBJECT_T();
  BEGIN
    glossary.put('tokens',p_tokens);
    glossary.put('textQuery',p_query);
    glossary.put('targets',p_matches);
    glossary.put('definitions',p_definitions);
    IF DBMS_LOB.GETLENGTH(glossary.to_clob())>40000 THEN
      RAISE_APPLICATION_ERROR(-20060,'Evidence/result limit exceeded; narrow the scope. Nothing is silently truncated.');
    END IF;
    response.put('formatVersion',1);
    response.put('question',p_question);
    response.put('schema',SYS_CONTEXT('USERENV','CURRENT_USER'));
    response.put('glossary',glossary);
    response.put('aiCalled',FALSE);
    RETURN response.to_clob();
  END;

  FUNCTION SEARCH(p_question CLOB) RETURN CLOB IS
    question_text VARCHAR2(32767);
    tokens JSON_ARRAY_T;
    query_text VARCHAR2(32767);
    candidates SYS_REFCURSOR;
    matches JSON_ARRAY_T;
    definitions JSON_ARRAY_T;
  BEGIN
    question_text:=validate_question(p_question);
    require_dictionary;
    tokens:=tokenize_question(question_text);
    query_text:=build_text_query(tokens);
    candidates:=find_term_candidates(query_text);
    matches:=match_question_terms(question_text,candidates);
    matches:=remove_overlapping_matches(matches);
    definitions:=load_term_definitions(matches);
    RETURN build_search_response(question_text,tokens,query_text,matches,definitions);
  END;
END DBC_GLOSSARY_API;
