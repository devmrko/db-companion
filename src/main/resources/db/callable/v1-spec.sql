CREATE PACKAGE {{PACKAGE}} AUTHID CURRENT_USER AS
  -- DB Companion callable AI v1
  -- CONTEXT: no AI. SQL: one showsql call. QUERY: same SQL, then one query.
  FUNCTION ASK(
    p_question CLOB,
    p_profile VARCHAR2,
    p_use_glossary NUMBER DEFAULT 1,
    p_use_ontology NUMBER DEFAULT 0,
    p_tables CLOB DEFAULT '[]',
    p_mode VARCHAR2 DEFAULT 'CONTEXT',
    p_max_rows NUMBER DEFAULT 200
  ) RETURN CLOB;
END DBC_AI_QUERY;
