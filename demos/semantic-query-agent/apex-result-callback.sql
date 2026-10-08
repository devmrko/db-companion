DECLARE
  l_response CLOB;
  l_pos PLS_INTEGER:=1;
BEGIN
  l_response:=DBC_AGENT_RESULT_STORE.read_result(apex_application.g_x01,apex_application.g_x02);
  WHILE l_pos<=DBMS_LOB.GETLENGTH(l_response) LOOP
    sys.htp.prn(DBMS_LOB.SUBSTR(l_response,8000,l_pos));
    l_pos:=l_pos+8000;
  END LOOP;
END;
