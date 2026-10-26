CREATE PACKAGE {{API}} AUTHID CURRENT_USER AS
  -- DB Companion glossary API v2: dictionary search only, no AI.
  FUNCTION SEARCH(p_question CLOB) RETURN CLOB;
END DBC_GLOSSARY_API;
