-- Read-only search of the existing dictionary. Bind question, candidate query, output CLOB.
-- The broad Text index supplies candidates, never the final attachment decision.
DECLARE
  question_text VARCHAR2(32767) := ?;
  candidate_query VARCHAR2(32767) := ?;
  hits JSON_ARRAY_T := JSON_ARRAY_T();
  targets JSON_ARRAY_T := JSON_ARRAY_T();
  item JSON_OBJECT_T;
  occurrences CLOB;
  span_no BINARY_INTEGER;
  active_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO active_count FROM {{TABLE}} WHERE ENABLED_YN='Y';
  IF active_count>2000 THEN
    RAISE_APPLICATION_ERROR(-20001,'Active dictionary term limit exceeded');
  END IF;
  FOR r IN (
    WITH candidates AS (
      SELECT TERM_ID,TERM_TEXT,ALIASES_JSON FROM {{TABLE}}
      WHERE ENABLED_YN='Y' AND CONTAINS(SEARCH_TEXT,candidate_query,1)>0
    ), labels AS (
      SELECT TERM_ID,TERM_TEXT AS LABEL,'TERM' AS KIND FROM candidates
      UNION ALL
      SELECT t.TERM_ID,a.LABEL,'ALIAS' AS KIND FROM candidates t,
        JSON_TABLE(t.ALIASES_JSON,'$[*]' COLUMNS(LABEL VARCHAR2(256 CHAR) PATH '$' ERROR ON ERROR)) a
    )
    SELECT DISTINCT TERM_ID,LABEL,KIND FROM labels
  ) LOOP
    DECLARE spans CTX_DOC.HIGHLIGHT_TAB;
    BEGIN
    CTX_DOC.POLICY_HIGHLIGHT(
      policy_name=>'DBC_BT_KO_POLICY',document=>question_text,
      text_query=>'{'||REPLACE(REPLACE(r.LABEL,'\','\\'),'}','\}')||'}',
      restab=>spans,plaintext=>TRUE,format=>'TEXT');
    span_no:=spans.FIRST;
    WHILE span_no IS NOT NULL LOOP
      -- Native offsets are UCS2; SUBSTR2 also preserves alignment after emoji.
      item:=JSON_OBJECT_T();
      item.put('termId',r.TERM_ID);item.put('label',r.LABEL);item.put('kind',r.KIND);
      item.put('start',spans(span_no).offset);
      item.put('end',spans(span_no).offset+spans(span_no).length);
      item.put('matched',SUBSTR2(question_text,spans(span_no).offset,spans(span_no).length));
      hits.append(item);
      IF hits.get_size()>512 THEN
        RAISE_APPLICATION_ERROR(-20001,'Dictionary phrase occurrence limit exceeded');
      END IF;
      span_no:=spans.NEXT(span_no);
    END LOOP;
    END;
  END LOOP;
  occurrences:=hits.to_clob();
  FOR r IN (
    WITH matched AS (
      SELECT j.*,
             UPPER(REGEXP_REPLACE(TRIM(j.LABEL),'[[:space:]]+',' ')) AS EXPRESSION
      FROM JSON_TABLE(occurrences,'$[*]' COLUMNS(
        TERM_ID VARCHAR2(36) PATH '$.termId',LABEL VARCHAR2(256 CHAR) PATH '$.label',
        KIND VARCHAR2(10) PATH '$.kind',START_POS NUMBER PATH '$.start',END_POS NUMBER PATH '$.end',
        MATCHED_TEXT VARCHAR2(4000 CHAR) PATH '$.matched')) j
      -- POLICY_HIGHLIGHT already matched the complete registered phrase using the lexer.
      -- Keep morphological variants; do not require the original substring to equal the label.
      -- Do not join separate expressions across added punctuation (e.g. "business / active").
      -- The non-word structure must match; no language-specific particle or stop-word lists.
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
    item:=JSON_OBJECT_T();item.put('expression',r.EXPRESSION);item.put('kind',r.KIND);
    item.put('termIds',JSON_ARRAY_T.parse(r.TERM_IDS));targets.append(item);
  END LOOP;
  DBMS_LOB.FREETEMPORARY(occurrences);
  ?:=targets.to_clob();
EXCEPTION
  WHEN OTHERS THEN
    IF DBMS_LOB.ISTEMPORARY(occurrences)=1 THEN DBMS_LOB.FREETEMPORARY(occurrences); END IF;
    RAISE;
END;
