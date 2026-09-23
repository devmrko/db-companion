CREATE PACKAGE {{OWNER}}."DBC_METADATA_AUDIT" AUTHID DEFINER AS
  -- DB Companion metadata history v1
  PROCEDURE capture_before(p_table VARCHAR2);
  PROCEDURE capture_after(p_table VARCHAR2);
END;
