CREATE PACKAGE {{OWNER}}."DBC_METADATA_ACCESS" AUTHID DEFINER AS
  -- DB Companion scoped metadata-history access v1
  FUNCTION inspect(p_schema VARCHAR2, p_table VARCHAR2) RETURN CLOB;
  FUNCTION entries(p_schema VARCHAR2, p_table VARCHAR2, p_page NUMBER) RETURN CLOB;
  PROCEDURE set_enabled(p_schema VARCHAR2, p_table VARCHAR2, p_enabled VARCHAR2);
END;
