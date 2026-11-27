DECLARE literal_length PLS_INTEGER;literal_pos PLS_INTEGER;
BEGIN
    -- Korean query-time tokenization can differ for a bare registered noun
    -- and that same noun with a particle in the document. If the registered
    -- phrase is literally present, ask the lexer about its actual surface form.
    -- Accept only when Oracle highlights EXACTLY the registered phrase span:
    -- no particle lists, prefix matches, or broad candidate-only acceptance.
    IF spans.COUNT=0 AND NOT REGEXP_LIKE({{LABEL}},'[^[:alnum:][:space:]_]') THEN
      literal_length:=LENGTH2({{LABEL}});literal_pos:=INSTR2({{QUESTION}},{{LABEL}});
      WHILE literal_pos>0 LOOP
        IF literal_pos=1 OR NOT REGEXP_LIKE(SUBSTR2({{QUESTION}},literal_pos-1,1),'[[:alnum:]_]') THEN
          DECLARE surface_end PLS_INTEGER:=literal_pos+literal_length;
                  surface_spans CTX_DOC.HIGHLIGHT_TAB;surface_no BINARY_INTEGER;
                  surface_text VARCHAR2(32767);
          BEGIN
            WHILE surface_end<=LENGTH2({{QUESTION}})
                  AND REGEXP_LIKE(SUBSTR2({{QUESTION}},surface_end,1),'[[:alnum:]_]') LOOP
              surface_end:=surface_end+1;
            END LOOP;
            IF surface_end>literal_pos+literal_length AND surface_end-literal_pos-literal_length<=128 THEN
              surface_text:=SUBSTR2({{QUESTION}},literal_pos,surface_end-literal_pos);
              CTX_DOC.POLICY_HIGHLIGHT(policy_name=>{{POLICY}},document=>{{QUESTION}},
                text_query=>'{'||REPLACE(REPLACE(surface_text,'\','\\'),'}','\}')||'}',
                restab=>surface_spans,plaintext=>TRUE,format=>'TEXT');
              surface_no:=surface_spans.FIRST;
              WHILE surface_no IS NOT NULL LOOP
                IF surface_spans(surface_no).offset=literal_pos AND surface_spans(surface_no).length=literal_length THEN
                  item:=JSON_OBJECT_T();item.put('termId',{{TERM_ID}});item.put('label',{{LABEL}});item.put('kind',{{KIND}});
                  item.put('start',literal_pos);item.put('end',literal_pos+literal_length);
                  item.put('matched',SUBSTR2({{QUESTION}},literal_pos,literal_length));hits.append(item);
                  IF hits.get_size()>512 THEN RAISE_APPLICATION_ERROR(-20001,'Dictionary phrase occurrence limit exceeded'); END IF;
                END IF;
                surface_no:=surface_spans.NEXT(surface_no);
              END LOOP;
            END IF;
          END;
        END IF;
        literal_pos:=INSTR2({{QUESTION}},{{LABEL}},literal_pos+1);
      END LOOP;
    END IF;
END;
