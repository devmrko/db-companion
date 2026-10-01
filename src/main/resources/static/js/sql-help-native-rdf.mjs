import {nativeRdfSql} from './sql-help-native-source.mjs';
const pair=(ko,en)=>({ko,en});
const doc='https://docs.oracle.com/en/database/oracle/oracle-database/26/rdfrm/quick-start-using-rdf-data-oracle-autonomous-ai-database.html';
const step=(id,title,en,header,footer,risk,note,english)=>({id:'native-'+id,title:pair(title,en),purpose:pair(title,en),sql:header+nativeRdfSql[id]+'\n/\n'+footer,source:'src/main/resources/sql/ontology-native/'+id+'.sql',risk,doc,note:pair(note,english)});
export const nativeRdfHelp=[
  {id:'native-flow',title:pair('Oracle 네이티브 RDF 처리 순서','Oracle native RDF workflow'),purpose:pair('① 데이터 사전에서 테이블·뷰·컬럼 메타데이터 수집 → ② Oracle RDF 네트워크의 이름 있는 그래프에 저장 → ③ SEM_MATCH와 SQL로 컬럼명·자료형 비교 → ④ 선택 후보를 온톨로지 새 초안 버전에 저장 → ⑤ 관계 화면에서 검토·승인.','1. Read dictionary metadata → 2. Save named graphs in Oracle RDF → 3. Compare names/types using SEM_MATCH and SQL → 4. Append selected candidates to ontology drafts → 5. Review and approve in Relationships.'),sql:`SELECT OBJECT_NAME, OBJECT_TYPE, STATUS
FROM USER_OBJECTS
WHERE OBJECT_NAME LIKE 'DBC!_META!_RDF#%' ESCAPE '!'
ORDER BY OBJECT_TYPE, OBJECT_NAME;

-- Saved ontology versions, including candidate relationships in payload.links.
SELECT OBJECT_NAME, REVISION, STATE, ACTOR, RECORDED_AT,
       JSON_QUERY(PAYLOAD, '$.links' RETURNING CLOB) AS LINKS
FROM DBC_ONTOLOGY_CATALOG
WHERE OBJECT_OWNER = USER AND OBJECT_NAME = '<TABLE_OR_VIEW>'
ORDER BY REVISION DESC;`,source:'src/main/java/com/dbcompanion/service/NativeMetadataService.java',risk:'read',doc,note:pair('RDF는 DBC_META_RDF 네트워크 / DBC_METADATA 모델에 저장합니다. 매 수집은 독립 스냅샷이며 기존 것은 유지합니다. 관계 후보는 DBC_ONTOLOGY_CATALOG.PAYLOAD.links에 origin=RDF, status=CANDIDATE와 수집 근거를 저장합니다. 동일 이름은 조인 정답·FK를 뜻하지 않습니다. 테이블·컬럼 메타데이터만 읽으며 AI는 호출하지 않습니다. 현재 온톨로지 질의는 저장된 승인 관계를 사용하는 앱 경로이며, 직접 SEM_MATCH 질의와는 구분됩니다.','RDF uses network DBC_META_RDF / model DBC_METADATA. Every capture adds a separate snapshot. Candidates are stored in DBC_ONTOLOGY_CATALOG.PAYLOAD.links with origin=RDF, status=CANDIDATE and capture evidence. Equal names do not prove a join or FK. No AI or business rows are used. Existing ontology query traverses approved saved links in the app; it is distinct from direct SEM_MATCH.')},
  {id:'native-jvm',title:pair('ADB의 RDF 조회 사전 조건: Java VM','ADB prerequisite: Java VM'),purpose:pair('Autonomous DB에서 SEM_MATCH에 필요한 Java VM을 관리자가 확인합니다.','An administrator checks the Java VM required by SEM_MATCH on Autonomous DB.'),sql:`-- ADMIN: inspect first. Enabling is a separate DB-level decision.
SELECT COMP_ID, STATUS, VERSION FROM DBA_REGISTRY WHERE COMP_ID = 'JAVAVM';

-- Only if the feature is not enabled, with explicit administrative approval:
BEGIN
  DBMS_CLOUD_ADMIN.ENABLE_FEATURE(feature_name => 'JAVAVM');
END;
/
-- Then perform a NORMAL database restart in OCI. Do not use restart without downtime.
-- After restart, recheck DBA_REGISTRY and run the RDF read example.`,source:'src/main/resources/sql/ontology-native/read.sql',risk:'ddl',doc:'https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/autonomous-oracle-java.html',note:pair('앱은 Java VM을 자동 활성화하거나 DB를 재시작하지 않습니다. DB 전체 기능 설정이며 활성화 후 비활성화할 수 없습니다. 지원 환경·권한·정상 재시작을 먼저 확인하세요. Java VM이 VALID여도 RDF 패키지 준비와 실제 SEM_MATCH 성공은 별도 확인합니다.','The app does not enable Java VM or restart the database. Enabling is a database-wide, non-reversible setting. Verify support, privileges and a normal restart. VALID Java VM alone does not prove RDF packages and SEM_MATCH are ready.')},
  step('setup','RDF 저장소·모델 생성','Create RDF storage/model','', '', 'ddl','로그인 소유 스키마에서 한 번 생성합니다. 동일 이름이 있으면 중단하며 자동 덮어쓰기·삭제·권한 부여는 하지 않습니다. DDL 중간 실패 시 이미 생성된 객체는 남을 수 있으므로 원인을 확인하세요.','Run once as the intended owner. Existing names stop setup; no automatic overwrite, cleanup or grants. DDL failures can leave partially created objects.'),
  step('capture','테이블·뷰 메타데이터를 RDF로 저장','Capture table/view metadata as RDF',`VARIABLE object_name VARCHAR2(128)
VARIABLE snapshot_iri VARCHAR2(100)
VARIABLE triple_count NUMBER
BEGIN :object_name := '<TABLE_OR_VIEW>'; END;
/
`, 'COMMIT;\nPRINT snapshot_iri\nPRINT triple_count', 'write','USER_TAB_COLUMNS·USER_TAB_COMMENTS·USER_COL_COMMENTS를 읽어 SDO_RDF_TRIPLE_S로 저장합니다. 원본 행·코멘트·업무 정의는 변경하지 않습니다. JDBC에서는 마지막 COMMIT을 앱 트랜잭션이 수행합니다. 출력한 snapshot_iri를 다음 조회에 사용하세요.','Reads dictionary names, types and comments and stores SDO_RDF_TRIPLE_S triples. Source rows/comments/definitions are unchanged. The app owns the JDBC transaction. Use the returned snapshot_iri for subsequent reads.'),
  step('read','SEM_MATCH로 수집 컬럼 조회','Read captured columns with SEM_MATCH',`VARIABLE snapshot_iri VARCHAR2(100)
VARIABLE columns REFCURSOR
BEGIN :snapshot_iri := '<CAPTURE_IRI>'; END;
/
`, 'PRINT columns', 'read','수집된 스냅샷의 컬럼·자료형·코멘트를 조회합니다. 현재 원본과 동일하다는 보장은 없으므로 필요하면 재수집하세요.','Reads the captured snapshot, not live source metadata. Recapture if the source changed.'),
  step('snapshots','객체별 최신 RDF 스냅샷 조회','Read latest RDF snapshots','VARIABLE snapshots REFCURSOR\n', 'PRINT snapshots', 'read','객체별 최신 수집 시각·수집자·IRI를 조회합니다. 같은 객체의 이전 스냅샷을 삭제하지 않습니다.','Lists the latest capture timestamp, actor and IRI per object; old snapshots are retained.'),
  step('candidates','Oracle SQL로 관계 후보 조회','Find relationship candidates with Oracle SQL',`VARIABLE snapshot_json CLOB
VARIABLE candidates REFCURSOR
BEGIN :snapshot_json := '["<CAPTURE_IRI_A>","<CAPTURE_IRI_B>"]'; END;
/
`, 'PRINT candidates', 'read','선택 스냅샷의 동일 컬럼명·자료형을 DB에서 비교합니다. 단일 컬럼 가설이며 PK·복합 키·데이터 의미·조인 중복은 별도 검토 대상입니다. 앱은 현재 정의와 수집본을 대조하고 선택 후보만 저장합니다. 최대 2,000건을 초과하면 일부만 저장하지 않고 범위 축소를 요구합니다.','The DB compares exact names/types in selected snapshots. Single-column hypotheses do not establish keys, semantics, or join multiplicity. The app checks captured/current definitions before storing selected candidates. More than 2,000 candidates requires narrowing, not silent truncation.'),
  {id:'native-graph',title:pair('승인된 관계로 업무 Property Graph 생성','Create a business Property Graph from approved links'),purpose:pair('RDF 메타데이터 그래프와 업무 행 Property Graph는 별개입니다. 승인 후 대상 키·조인 조건을 확인해 생성합니다.','RDF metadata and a business-row Property Graph are separate. Verify approved joins and keys before creating it.'),sql:`-- Example only: verify actual unique vertex/edge keys and approved joins first.
CREATE PROPERTY GRAPH "<PROPERTY_GRAPH>"
  VERTEX TABLES (
    "<PARENT_TABLE>" KEY ("<PARENT_KEY>")
      LABEL PARENT PROPERTIES ("<PARENT_KEY>")
  )
  EDGE TABLES (
    "<RELATION_TABLE>" KEY ("<EDGE_KEY>")
      SOURCE KEY ("<SOURCE_KEY>") REFERENCES "<PARENT_TABLE>" ("<PARENT_KEY>")
      DESTINATION KEY ("<TARGET_KEY>") REFERENCES "<PARENT_TABLE>" ("<PARENT_KEY>")
      LABEL CONNECTS PROPERTIES ("<EDGE_KEY>")
  );`,source:'src/main/java/com/dbcompanion/model/PropertyGraph.java',risk:'ddl',doc:'https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-property-graph.html',note:pair('예시는 승인된 연결 테이블을 간선으로 사용하는 최소 구조입니다. RDF 후보를 저장했다고 FK나 Property Graph가 자동 생성되지 않습니다. 키가 없는 일반 뷰의 동일 GUID만으로 고유성을 가정하지 마세요. 앱의 기존 Property Graph 전송 계획·검증·생성은 별도입니다.','Minimal example with an approved relationship table as edges. Saving RDF candidates does not create FKs or a Property Graph. Matching GUIDs on views does not prove uniqueness. Existing app graph planning, validation and creation are separate.')}
];
