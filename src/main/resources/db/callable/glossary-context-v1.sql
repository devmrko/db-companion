CREATE FUNCTION {{CONTEXT}}(p_context CLOB)
  RETURN CLOB AUTHID CURRENT_USER AS
  -- DB Companion semantic glossary callback v1. JSON types escape all text.
  context_obj JSON_OBJECT_T;
  response_obj JSON_OBJECT_T;
  glossary_obj JSON_OBJECT_T;
  definitions JSON_ARRAY_T;
  result_array JSON_ARRAY_T := JSON_ARRAY_T();
  entry_obj JSON_OBJECT_T;
  item_obj JSON_OBJECT_T;
  value_element JSON_ELEMENT_T;
  question CLOB;
  evidence CLOB;
  definition_text CLOB;
  criteria_text CLOB;
  output_text CLOB;
  invalid_input EXCEPTION;
  PRAGMA EXCEPTION_INIT(invalid_input,-20064);
  invalid_result EXCEPTION;
  PRAGMA EXCEPTION_INIT(invalid_result,-20065);
BEGIN
  BEGIN
    context_obj := JSON_OBJECT_T.parse(p_context);
    context_obj.on_error(1);
    value_element := context_obj.get('user_prompt');
    IF value_element IS NULL OR NOT value_element.is_string() THEN
      RAISE invalid_input;
    END IF;
    question := context_obj.get_clob('user_prompt');
    IF question IS NULL OR DBMS_LOB.GETLENGTH(question)>8000
      OR TRIM(DBMS_LOB.SUBSTR(question,8000,1)) IS NULL THEN
      RAISE invalid_input;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE_APPLICATION_ERROR(-20064,
      'DBC_SEM_INVALID_INPUT: expected a JSON object with user_prompt containing 1 to 8000 characters; use JSON_OBJECT_T to escape quotes, backslashes and newlines.');
  END;
  -- Preserve lookup errors, including missing dictionary and access failures.
  evidence := {{API}}.SEARCH(question);
  BEGIN
    response_obj := JSON_OBJECT_T.parse(evidence);
    response_obj.on_error(1);
    glossary_obj := response_obj.get_object('glossary');
    glossary_obj.on_error(1);
    definitions := glossary_obj.get_array('definitions');
    IF definitions IS NULL THEN RAISE invalid_result; END IF;
    FOR i IN 0..definitions.get_size-1 LOOP
      entry_obj := TREAT(definitions.get(i) AS JSON_OBJECT_T);
      entry_obj.on_error(1);
      value_element := entry_obj.get('term');
      IF value_element IS NULL OR NOT value_element.is_string() THEN RAISE invalid_result; END IF;
      IF TRIM(entry_obj.get_string('term')) IS NULL THEN RAISE invalid_result; END IF;
      value_element := entry_obj.get('definition');
      IF value_element IS NULL OR NOT value_element.is_string() THEN RAISE invalid_result; END IF;
      definition_text := entry_obj.get_clob('definition');
      IF definition_text IS NULL OR TRIM(DBMS_LOB.SUBSTR(definition_text,32767,1)) IS NULL THEN
        RAISE invalid_result;
      END IF;
      IF entry_obj.has('criteria') THEN
        value_element := entry_obj.get('criteria');
        IF NOT value_element.is_null() THEN
          IF NOT value_element.is_string() THEN RAISE invalid_result; END IF;
          criteria_text := entry_obj.get_clob('criteria');
          definition_text := definition_text || CHR(10) || 'SQL criteria: ' || criteria_text;
        END IF;
      END IF;
      item_obj := JSON_OBJECT_T();
      item_obj.put('term',entry_obj.get_string('term'));
      item_obj.put('definition',definition_text);
      item_obj.put('applies_to',JSON_ARRAY_T());
      result_array.append(item_obj);
    END LOOP;
    output_text := result_array.to_clob();
    IF DBMS_LOB.GETLENGTH(output_text)>40000 THEN RAISE invalid_result; END IF;
    RETURN output_text;
  EXCEPTION WHEN OTHERS THEN
    RAISE_APPLICATION_ERROR(-20065,
      'DBC_SEM_INVALID_RESULT: expected glossary.definitions with nonblank term and definition strings, optional string criteria, and output within 40000 characters; JSON_OBJECT_T handles escaping.');
  END;
END DBC_GLOSSARY_CONTEXT;
