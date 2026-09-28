CREATE PACKAGE BODY {{OWNER}}."DBC_METADATA_AUDIT" AS
  -- DB Companion metadata history v3: include view comment snapshots
  TYPE item IS RECORD (
    column_name VARCHAR2(128), kind VARCHAR2(16), annotation_name VARCHAR2(32767), value_text VARCHAR2(32767));
  TYPE snapshot IS TABLE OF item INDEX BY VARCHAR2(32767);
  g_before snapshot;
  g_table VARCHAR2(128);
  g_event RAW(16);

  PROCEDURE take_snapshot(p_table VARCHAR2, p_result OUT NOCOPY snapshot) IS
    v_count PLS_INTEGER := 0;
    v_type VARCHAR2(30);
    PROCEDURE add_item(p_column VARCHAR2, p_kind VARCHAR2, p_name VARCHAR2, p_value VARCHAR2) IS
      v_key VARCHAR2(32767) := p_kind || ':' || NVL(LENGTH(p_column), 0) || ':' || p_column || ':' || p_name;
    BEGIN
      IF p_result.EXISTS(v_key) THEN
        RAISE_APPLICATION_ERROR(-20081, 'Duplicate metadata identity');
      END IF;
      p_result(v_key).column_name := p_column;
      p_result(v_key).kind := p_kind;
      p_result(v_key).annotation_name := p_name;
      p_result(v_key).value_text := p_value;
    END;
  BEGIN
    p_result.DELETE;
    FOR r IN (SELECT comments, 'TABLE' AS table_type FROM sys.all_mview_comments
              WHERE owner = {{OWNER_LITERAL}} AND mview_name = p_table
              UNION ALL
              SELECT c.comments, c.table_type FROM sys.all_tab_comments c
              WHERE c.owner = {{OWNER_LITERAL}} AND c.table_name = p_table AND c.table_type IN ('TABLE', 'VIEW')
                AND NOT EXISTS (SELECT 1 FROM sys.all_mviews m
                  WHERE m.owner = {{OWNER_LITERAL}} AND m.mview_name = p_table)) LOOP
      add_item(NULL, 'COMMENT', NULL, r.comments);
      v_count := v_count + 1;
      v_type := r.table_type;
    END LOOP;
    IF v_count <> 1 THEN RAISE_APPLICATION_ERROR(-20082, 'Table or view metadata is not visible'); END IF;
    FOR r IN (SELECT column_name, comments FROM sys.all_col_comments
              WHERE owner = {{OWNER_LITERAL}} AND table_name = p_table) LOOP
      add_item(r.column_name, 'COMMENT', NULL, r.comments);
    END LOOP;
    IF v_type = 'TABLE' THEN
      FOR r IN (SELECT column_name, annotation_name, annotation_value FROM sys.all_annotations_usage
              WHERE annotation_owner = {{OWNER_LITERAL}} AND object_name = p_table
                AND object_type = 'TABLE' AND domain_name IS NULL) LOOP
        add_item(r.column_name, 'ANNOTATION', r.annotation_name, r.annotation_value);
      END LOOP;
    END IF;
  END;

  FUNCTION value_json(p_present BOOLEAN, p_value VARCHAR2) RETURN CLOB IS
    v_json JSON_OBJECT_T := JSON_OBJECT_T();
  BEGIN
    v_json.put('exists', p_present);
    IF p_value IS NULL THEN v_json.put_null('value'); ELSE v_json.put('value', p_value); END IF;
    RETURN v_json.to_clob();
  END;

  PROCEDURE record_change(p_item item, p_old_exists BOOLEAN, p_old VARCHAR2, p_new_exists BOOLEAN, p_new VARCHAR2) IS
    v_old CLOB := value_json(p_old_exists, p_old);
    v_new CLOB := value_json(p_new_exists, p_new);
  BEGIN
    INSERT INTO {{OWNER}}."DBC_METADATA_HISTORY"
      (event_id, changed_at, changed_by, schema_name, table_name, column_name, change_kind, annotation_name, before_json, after_json)
    VALUES (g_event, SYSTIMESTAMP, SYS_CONTEXT('USERENV','SESSION_USER'), {{OWNER_LITERAL}},
            g_table, p_item.column_name, p_item.kind, p_item.annotation_name, v_old, v_new);
    IF DBMS_LOB.istemporary(v_old) = 1 THEN DBMS_LOB.freetemporary(v_old); END IF;
    IF DBMS_LOB.istemporary(v_new) = 1 THEN DBMS_LOB.freetemporary(v_new); END IF;
  EXCEPTION WHEN OTHERS THEN
    IF DBMS_LOB.istemporary(v_old) = 1 THEN DBMS_LOB.freetemporary(v_old); END IF;
    IF DBMS_LOB.istemporary(v_new) = 1 THEN DBMS_LOB.freetemporary(v_new); END IF;
    RAISE;
  END;

  PROCEDURE capture_before(p_table VARCHAR2) IS
    v_enabled CHAR(1);
  BEGIN
    g_before.DELETE; g_table := NULL; g_event := NULL;
    BEGIN
      SELECT enabled INTO v_enabled FROM {{OWNER}}."DBC_METADATA_TRACKING"
        WHERE table_name = p_table FOR UPDATE;
    EXCEPTION WHEN NO_DATA_FOUND THEN RETURN;
    END;
    IF v_enabled <> 'Y' THEN RETURN; END IF;
    g_table := p_table; g_event := SYS_GUID();
    take_snapshot(p_table, g_before);
  EXCEPTION WHEN OTHERS THEN
    g_before.DELETE; g_table := NULL; g_event := NULL;
    RAISE;
  END;

  PROCEDURE capture_after(p_table VARCHAR2) IS
    v_after snapshot;
    v_key VARCHAR2(32767);
    v_count PLS_INTEGER;
  BEGIN
    IF g_table IS NULL OR g_table <> p_table THEN
      SELECT COUNT(*) INTO v_count FROM {{OWNER}}."DBC_METADATA_TRACKING" WHERE table_name = p_table AND enabled = 'Y';
      IF v_count > 0 THEN RAISE_APPLICATION_ERROR(-20083, 'Metadata history baseline is missing'); END IF;
      RETURN;
    END IF;
    take_snapshot(p_table, v_after);
    v_key := g_before.FIRST;
    WHILE v_key IS NOT NULL LOOP
      IF NOT v_after.EXISTS(v_key) THEN
        record_change(g_before(v_key), TRUE, g_before(v_key).value_text, FALSE, NULL);
      ELSIF g_before(v_key).value_text <> v_after(v_key).value_text
         OR (g_before(v_key).value_text IS NULL AND v_after(v_key).value_text IS NOT NULL)
         OR (g_before(v_key).value_text IS NOT NULL AND v_after(v_key).value_text IS NULL) THEN
        record_change(v_after(v_key), TRUE, g_before(v_key).value_text, TRUE, v_after(v_key).value_text);
      END IF;
      v_key := g_before.NEXT(v_key);
    END LOOP;
    v_key := v_after.FIRST;
    WHILE v_key IS NOT NULL LOOP
      IF NOT g_before.EXISTS(v_key) THEN
        record_change(v_after(v_key), FALSE, NULL, TRUE, v_after(v_key).value_text);
      END IF;
      v_key := v_after.NEXT(v_key);
    END LOOP;
    g_before.DELETE; g_table := NULL; g_event := NULL;
  EXCEPTION WHEN OTHERS THEN
    g_before.DELETE; g_table := NULL; g_event := NULL;
    RAISE;
  END;
END;
