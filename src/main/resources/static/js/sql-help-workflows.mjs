// Source-backed additions from the SQL? audit. No live values or executable UI actions.
const pair=(ko,en)=>({ko,en});
const read=(id,ko,en,sql,source,koNote,enNote,extra={})=>({id,title:pair(ko,en),purpose:pair(ko,en),sql,
  source:`src/main/java/com/dbcompanion/${source}.java`,risk:'read',note:pair(koNote,enNote),...extra});
export const taskDefinition=read('task-definition','Task 정의·연결 Tool 조회','Read Task definition and linked Tools',`-- Current login's definitions, NOT execution history.
SELECT * FROM USER_AI_AGENT_TASKS WHERE TASK_NAME = '<TASK_NAME>';
SELECT * FROM USER_AI_AGENT_TASK_ATTRIBUTES WHERE TASK_NAME = '<TASK_NAME>';
-- Resolve each Tool name from the task's tools attribute, then query it:
SELECT * FROM USER_AI_AGENT_TOOLS WHERE TOOL_NAME = '<TOOL_NAME_FROM_TASK_TOOLS>';`,
  'repository/AgentCatalogRepository',
  'Task의 tools 속성에 등록된 Tool을 조회합니다. 앱은 먼저 Team의 agents 속성에서 Task 소속을 확인합니다. 위 SQL은 소속 검사를 대신하지 않습니다. DB 버전에 따라 TASK_NAME/AGENT_TASK_NAME/NAME 등 컬럼이 다를 수 있어 앱은 조회 전 컬럼을 확인합니다. 다른 스키마는 권한이 있는 DBA_* 뷰에서 OWNER까지 제한해야 합니다. 실행 이력은 별도 agent-runs 작업입니다.',
  'Resolve Tool names from the task tools attribute. The app first checks task membership in the team agents attribute; these queries do not reproduce that check. Column names vary (TASK_NAME/AGENT_TASK_NAME/NAME); the app inspects them first. Cross-schema access requires authorized DBA_* views filtered by OWNER. Run history is the separate agent-runs operation.');

export const delegatedHistory=read('history-delegated','위임받은 메타데이터 이력 조회','Read delegated metadata history',`-- Connect as the delegated DB user. AUDIT_OWNER is the recorded installer.
-- SQLcl: inspect state/capabilities and read page 1 (CLOB JSON).
SET LONG 1000000
SELECT "<AUDIT_OWNER>"."DBC_METADATA_ACCESS".inspect('<TARGET_SCHEMA>', '<TABLE_OR_VIEW>') AS STATUS_JSON
FROM SYS.DUAL;
SELECT "<AUDIT_OWNER>"."DBC_METADATA_ACCESS".entries('<TARGET_SCHEMA>', '<TABLE_OR_VIEW>', 1) AS HISTORY_JSON
FROM SYS.DUAL;`, 'repository/HistoryAccessRepository',
  '직접 저장소 조회 권한이 없는 위임 사용자의 경로입니다. AUDIT_OWNER는 선택한 스키마가 아니라 설치 확인에서 확인한 트리거 소유자입니다. 패키지 EXECUTE와 대상별 이력 조회 권한이 모두 필요합니다. SESSION_USER로 매번 검사하며 일반 업무 데이터 권한을 부여하지 않습니다. 페이지는 1부터 시작하고 10건 표시 + 다음 페이지 확인용 1건을 반환합니다. 조회·관리 권한은 기록할 사용자를 필터링하지 않습니다. 관리 권한의 set_enabled는 기존 수집기의 ON/OFF만 가능하고 설치·권한 부여는 하지 못합니다.',
  'For delegates without direct store access. AUDIT_OWNER is the verified trigger installer, not the selected schema. Package EXECUTE plus object-scoped history read permission are required and checked using SESSION_USER; no business-data privileges are conferred. Pages start at 1: ten display rows plus one lookahead row. Access rules do not filter whose changes are collected. Management set_enabled only toggles existing collectors; it cannot install them or grant access.',
  {permission:pair('위임받은 DB 사용자로 접속합니다. 설치 계정의 DBC_METADATA_ACCESS EXECUTE와 대상별 이력 조회 허용이 필요합니다. 원본 이력 테이블의 직접 SELECT 권한은 이 경로의 필수 조건이 아닙니다. 앱의 설치자 탐색에는 대상 스키마 DBC_METADATA_TRACKING의 READ가 별도로 필요할 수 있습니다.','Connect as the delegated DB user. Requires EXECUTE on the installer\'s DBC_METADATA_ACCESS and object-scoped read allowance, not direct SELECT on the history table. Application installer discovery may additionally require READ on the target schema DBC_METADATA_TRACKING.')});

export const resultReview=read('result-review','실행 결과 설명·AI 검토','Explain and review an executed result',`-- SQLcl. Bind the COMPLETE prepared review prompt from the app as CLOB.
-- Do not substitute the original question alone or include result cell values.
SET LONG 1000000
VARIABLE prepared_review_prompt CLOB
VARIABLE ai_response CLOB
BEGIN
  IF :prepared_review_prompt IS NULL THEN
    RAISE_APPLICATION_ERROR(-20051, 'Bind the prepared review prompt first');
  END IF;
  IF DBMS_CLOUD_AI.GET_CONVERSATION_ID IS NOT NULL THEN
    RAISE_APPLICATION_ERROR(-20051, 'Active conversation is not allowed');
  END IF;
  :ai_response := DBMS_CLOUD_AI.GENERATE(
    prompt => :prepared_review_prompt, profile_name => '<REVIEW_PROFILE>',
    action => 'chat', attributes => '{"conversation":false}');
END;
/
PRINT ai_response`, 'model/SelectAiResultReview',
  '성공한 실행의 resultId·SQL 해시·시각을 대조한 뒤 원 질문, 실행 SQL, 당시 적용한 용어·온톨로지, 확정 조건, 반환 행 수·잘림 여부·컬럼별 NULL/잘림 개수, 선택 비교 기준을 프롬프트로 만듭니다. 결과 셀 값은 보내지 않습니다. 사용자가 넣은 비교 기준과 SQL 자체에는 민감한 값이 있을 수 있으므로 전송 전에 확인하세요. 검토 프로필에 chat 1회이며 SQL 재실행·자동 재시도는 없습니다. 이 예제는 호출부이며 앱의 스냅샷 검사·프롬프트 조립을 재현하지 않습니다. AI 설명은 참고 의견이며 실제 수치 정답이나 VPD 적용의 검증이 아닙니다.',
  'The app verifies resultId, SQL hash and execution time, then prepares the question, executed SQL, captured glossary/ontology, confirmed conditions, returned-row/truncation summaries, per-column NULL/clipped counts and optional baseline. Result cell values are not sent. SQL and user-supplied baselines may themselves contain sensitive values: review before sending. One chat call uses the reviewer profile, with no SQL rerun or automatic retry. This example is only the call, not snapshot validation or prompt assembly. Advisory analysis does not prove numeric correctness or VPD enforcement.',{risk:'ai'});

export const discoveryHistory=read('discovery-history','저장된 관계 분석 계획·호출 기록','Read saved relationship plans and call receipts',`-- Login-owned app store. A saved plan is not a completed AI analysis.
SELECT RECORD_ID, RECORDED_AT,
       JSON_VALUE(PAYLOAD, '$.profile.selection.name') AS PROFILE_NAME,
       JSON_VALUE(PAYLOAD, '$.tables') AS TABLE_COUNT,
       JSON_VALUE(PAYLOAD, '$.calls') AS PLANNED_CALLS
FROM "<LOGIN_SCHEMA>"."DBC_APP_RECORD"
WHERE RECORD_TYPE = 'ONTOLOGY_DISCOVERY_PLAN' AND STATE = 'SUCCEEDED'
  AND JSON_VALUE(PAYLOAD, '$.database') = '<DATABASE_ID>'
ORDER BY SEQ DESC FETCH FIRST 20 ROWS ONLY;

SELECT RECORD_ID, STATE, RECORDED_AT, UPDATED_AT,
       JSON_VALUE(PAYLOAD, '$.index' RETURNING NUMBER) + 1 AS CALL_NUMBER,
       JSON_VALUE(PAYLOAD, '$.stage') AS STAGE,
       JSON_QUERY(PAYLOAD, '$.relations' RETURNING CLOB) AS RELATIONS_JSON,
       JSON_QUERY(PAYLOAD, '$.issues' RETURNING CLOB) AS ISSUES_JSON
FROM "<LOGIN_SCHEMA>"."DBC_APP_RECORD"
WHERE RECORD_TYPE = 'ONTOLOGY_DISCOVERY_CALL'
  AND JSON_VALUE(PAYLOAD, '$.runId') = '<RUN_UUID>'
ORDER BY SEQ;`, 'repository/OntologyDiscoveryRepository',
  '계획의 SUCCEEDED는 계획 저장 성공이지 모든 AI 호출 성공이 아닙니다. 각 호출의 STATE·stage·issues를 따로 보세요. index는 저장 시 0부터 시작하므로 화면 호출 번호는 +1입니다. 정상 후보는 DBC_ONTOLOGY_CATALOG의 새 버전 links와 RDF에 CANDIDATE로 저장되고 승인 관계와 구분됩니다. 호출 receipt의 relations만 보고 최종 저장·승인을 단정하지 마세요. 미시도 호출은 기록이 없고 실패·응답 불명 호출은 자동 재시도하지 않습니다. 재접속 후 계획을 불러와 현재 정의를 확인하고 동의한 범위만 재개합니다. SAVE 단계 복구는 저장만 재시도하며 AI를 호출하지 않습니다. 이 SQL은 조회만 하고 복구·승인을 수행하지 않습니다.',
  'Plan SUCCEEDED means the plan was stored, not that all AI calls succeeded. Inspect each receipt STATE, stage and issues. Stored indices start at zero; display call numbers add one. Valid candidates are appended to ontology links and RDF as CANDIDATE, not approved relationships. Receipt relations alone do not prove final persistence or approval. Unattempted calls have no receipt; failed/unknown calls are not automatically retried. Resume a stored plan only after definition checks and consent. SAVE-stage recovery retries persistence only, without AI. These queries neither recover nor approve anything.');

export const ordsChanges=[
  read('publish','기존 모듈 게시·미게시 전환','Publish or unpublish an existing module',`BEGIN
  ORDS.PUBLISH_MODULE(p_module_name => '<MODULE>', p_status => '<PUBLISHED_OR_NOT_PUBLISHED>');
END;
/
COMMIT;`, 'service/OrdsManagementService',
  '로그인 소유 모듈의 게시 상태만 바꿉니다. PUBLISHED 또는 NOT_PUBLISHED를 지정합니다. 템플릿·핸들러를 재정의하지 않습니다. 게시하면 해당 경로로 접근할 수 있으므로 개별 API 인증을 별도로 확인하세요. 앱은 최신 정의와 권한을 재검사하고 성공 시 트랜잭션을 커밋합니다.',
  'Changes only publication status of a login-owned module. Use PUBLISHED or NOT_PUBLISHED; templates/handlers are not redefined. Publication can expose endpoints; check API authentication separately. The app rechecks current definitions and permissions before committing.',{risk:'write'}),
  read('module-path','기존 모듈 경로 변경·하위 정의 보존','Change a module path while preserving descendants',`BEGIN
  ORDS.RENAME_MODULE(p_module_name => '<MODULE>', p_new_base_path => '<NEW_BASE_PATH>');
  ORDS.PUBLISH_MODULE(p_module_name => '<MODULE>', p_status => '<PUBLISHED_OR_NOT_PUBLISHED>');
END;
/
COMMIT;`, 'service/OrdsManagementService',
  '템플릿 등이 있는 기존 모듈 편집 경로입니다. 모듈 이름은 유지하고 base path와 게시 상태만 변경하며 하위 정의를 보존합니다. 새 경로는 /로 끝나야 합니다. 호출 URL이 바뀌므로 호출자를 확인하세요. 앱에서는 이 경로로 페이지 크기·설명을 함께 바꾸지 않습니다. 직접 실행은 앱의 동시 변경 검사를 대신하지 않습니다. 설치된 ORDS의 API 지원 여부를 확인하세요.',
  'Edit path for populated modules. Keeps the module name and descendants, changing only base path and publication status. End the new path with /. Endpoint URLs change: check callers. The app does not change page size/comments in this path. Manual SQL does not reproduce concurrency checks; confirm installed ORDS API support.',{risk:'write'})
];
