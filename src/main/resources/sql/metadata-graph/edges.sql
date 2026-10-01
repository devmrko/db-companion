WITH selected AS (
  SELECT SEQ, OBJECT_OWNER, OBJECT_NAME, PAYLOAD
  FROM @@CATALOG@@ WHERE OBJECT_OWNER=@@OWNER@@ AND SEQ IN (@@SEQS@@)
), cols AS (
  SELECT c.SEQ, j.col_pos, j.col_name
  FROM selected c, JSON_TABLE(c.PAYLOAD, '$.source.columns[*]' ERROR ON ERROR COLUMNS (
    col_pos FOR ORDINALITY, col_name VARCHAR2(128) PATH '$.name' ERROR ON ERROR
  )) j
), approved_links AS (
  SELECT c.SEQ AS source_seq, t.SEQ AS target_seq,
         l.id AS relation_id, l.label AS relation_label,
         l.condition_text, l.origin, 'APPROVED' AS relation_state,
         l.from_cols, l.to_cols
  FROM selected c,
    JSON_TABLE(c.PAYLOAD, '$.links[*]' ERROR ON ERROR COLUMNS (
      id VARCHAR2(64) PATH '$.id', status VARCHAR2(16) PATH '$.status',
      target_owner VARCHAR2(128) PATH '$.targetSchema',
      target_name VARCHAR2(128) PATH '$.targetTable',
      label VARCHAR2(320) PATH '$.label', condition_text VARCHAR2(4000) PATH '$.condition',
      origin VARCHAR2(16) PATH '$.origin',
      from_cols CLOB FORMAT JSON PATH '$.sourceColumns',
      to_cols CLOB FORMAT JSON PATH '$.targetColumns'
    )) l, selected t
  WHERE t.OBJECT_OWNER=l.target_owner AND t.OBJECT_NAME=l.target_name
    AND l.status='APPROVED' AND (@@LINK_FILTER@@)
), verified_fks AS (
  SELECT c.SEQ AS source_seq, t.SEQ AS target_seq,
         'FK:'||k.name AS relation_id, k.name AS relation_label,
         CAST(NULL AS VARCHAR2(4000)) AS condition_text,
         'FK' AS origin, 'FK' AS relation_state, k.from_cols, k.to_cols
  FROM selected c,
    JSON_TABLE(c.PAYLOAD, '$.source.keys[*]' ERROR ON ERROR COLUMNS (
      name VARCHAR2(128) PATH '$.name', kind VARCHAR2(1) PATH '$.type',
      status VARCHAR2(16) PATH '$.status', validated VARCHAR2(16) PATH '$.validated',
      target_owner VARCHAR2(128) PATH '$.targetOwner',
      target_name VARCHAR2(128) PATH '$.targetTable',
      from_cols CLOB FORMAT JSON PATH '$.columns',
      to_cols CLOB FORMAT JSON PATH '$.targetColumns'
    )) k, selected t
  WHERE t.OBJECT_OWNER=k.target_owner AND t.OBJECT_NAME=k.target_name
    AND k.kind='R' AND k.status='ENABLED' AND k.validated='VALIDATED'
    AND (@@FK_FILTER@@)
), mappings AS (
  SELECT r.source_seq,r.target_seq,r.relation_id,r.relation_label,
         r.condition_text,r.origin,r.relation_state,
         a.pos AS mapping_position, s.col_pos AS source_position,
         t.col_pos AS target_position,
         COUNT(*) OVER (PARTITION BY r.source_seq,r.relation_id) AS mapping_count
  FROM (SELECT * FROM approved_links UNION ALL SELECT * FROM verified_fks) r,
    JSON_TABLE(r.from_cols, '$[*]' ERROR ON ERROR COLUMNS (
      pos FOR ORDINALITY, col_name VARCHAR2(128) PATH '$' ERROR ON ERROR)) a,
    JSON_TABLE(r.to_cols, '$[*]' ERROR ON ERROR COLUMNS (
      pos FOR ORDINALITY, col_name VARCHAR2(128) PATH '$' ERROR ON ERROR)) b,
    cols s, cols t
  WHERE a.pos=b.pos AND s.SEQ=r.source_seq AND s.col_name=a.col_name
    AND t.SEQ=r.target_seq AND t.col_name=b.col_name
)
SELECT CAST('H:'||TO_CHAR(c.SEQ)||':'||TO_CHAR(c.col_pos) AS VARCHAR2(256)) AS EDGE_ID,
       CAST('O:'||TO_CHAR(c.SEQ) AS VARCHAR2(160)) AS SOURCE_ID,
       CAST('C:'||TO_CHAR(c.SEQ)||':'||TO_CHAR(c.col_pos) AS VARCHAR2(160)) AS TARGET_ID,
       CAST('CONTAINS' AS VARCHAR2(32)) AS EDGE_KIND,
       CAST(NULL AS VARCHAR2(200)) AS RELATION_ID,
       CAST(NULL AS VARCHAR2(320)) AS RELATION_LABEL,
       CAST(NULL AS VARCHAR2(4000)) AS CONDITION_TEXT,
       CAST('METADATA' AS VARCHAR2(16)) AS ORIGIN,
       CAST('STRUCTURE' AS VARCHAR2(16)) AS RELATION_STATE,
       0 AS MAPPING_POSITION, 0 AS MAPPING_COUNT
FROM cols c
UNION ALL
SELECT CAST('R:'||TO_CHAR(m.source_seq)||':'||m.relation_id AS VARCHAR2(256)),
       'O:'||TO_CHAR(m.source_seq), 'O:'||TO_CHAR(m.target_seq), 'RELATES_TO',
       TO_CHAR(m.source_seq)||':'||m.relation_id, m.relation_label,
       m.condition_text,m.origin,m.relation_state,0,m.mapping_count
FROM mappings m WHERE m.mapping_position=1
UNION ALL
SELECT CAST('M:'||TO_CHAR(m.source_seq)||':'||m.relation_id||':'||TO_CHAR(m.mapping_position) AS VARCHAR2(256)),
       'C:'||TO_CHAR(m.source_seq)||':'||TO_CHAR(m.source_position),
       'C:'||TO_CHAR(m.target_seq)||':'||TO_CHAR(m.target_position), 'MAPS_TO',
       TO_CHAR(m.source_seq)||':'||m.relation_id, m.relation_label,
       m.condition_text,m.origin,m.relation_state,m.mapping_position,m.mapping_count
FROM mappings m
