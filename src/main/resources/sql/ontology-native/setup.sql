-- Run once as the intended RDF owner, in a dedicated session.
-- DDL commits implicitly. Existing names are never reused or overwritten.
-- SEM_APIS is the documented public synonym (26ai may resolve it to RDF_APIS).
DECLARE
  v_count PLS_INTEGER;
  v_tablespace VARCHAR2(128);
BEGIN
  IF SYS_CONTEXT('USERENV','CURRENT_SCHEMA') <> USER THEN
    RAISE_APPLICATION_ERROR(-20001, 'Connect as the intended RDF owner.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM SESSION_PRIVS WHERE PRIVILEGE IN
    ('CREATE TABLE','CREATE VIEW','CREATE PROCEDURE','CREATE SEQUENCE','CREATE TRIGGER','CREATE TYPE');
  IF v_count <> 6 THEN
    RAISE_APPLICATION_ERROR(-20004, 'Check RDF owner creation privileges before setup.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM USER_OBJECTS
   WHERE OBJECT_NAME LIKE 'DBC!_META!_RDF#%' ESCAPE '!';
  IF v_count <> 0 THEN
    RAISE_APPLICATION_ERROR(-20002, 'RDF namespace already exists; inspect it. No automatic replacement.');
  END IF;
  SELECT DEFAULT_TABLESPACE INTO v_tablespace FROM USER_USERS;
  IF v_tablespace IN ('SYSTEM','SYSAUX') THEN
    RAISE_APPLICATION_ERROR(-20003, 'Use an application tablespace.');
  END IF;
  SEM_APIS.CREATE_RDF_NETWORK(v_tablespace,
    network_owner => USER, network_name => 'DBC_META_RDF');
  SEM_APIS.CREATE_RDF_GRAPH('DBC_METADATA', NULL, NULL,
    network_owner => USER, network_name => 'DBC_META_RDF');
END;
