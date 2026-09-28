// Copy-only, source-backed recipes. Values are placeholders, never session/customer data.
// Each recipe is a DB operation, not a claim that SQL reproduces Java validation or UI state.
const aiDoc='https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/dbms-cloud-ai-package.html';
const dbDoc='https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/';
const pair=(ko,en)=>({ko,en});
const source=name=>`src/main/java/com/dbcompanion/${name}.java`;
function recipe(id,ko,en,sql,origin,options={}) {
  return {id,title:pair(ko,en),purpose:pair(ko,en),sql,source:source(origin),risk:'read',doc:dbDoc,
    note:pair('앱의 바인드 변수를 예시 값으로 바꾼 구문입니다. 앱의 권한·동시 변경 검사와 이력 저장은 별도입니다.','Source SQL adapted with placeholders. Application permission checks, concurrency checks and history capture are separate.'),...options};
}
const attributes=`SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE
FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES
WHERE PROFILE_NAME = '<PROFILE_NAME>'
ORDER BY ATTRIBUTE_NAME;`;
const profileList=`SELECT PROFILE_NAME, STATUS, DESCRIPTION, PROFILE_ID, CREATED, LAST_MODIFIED
FROM USER_CLOUD_AI_PROFILES
ORDER BY PROFILE_NAME;`;
const profileOption=(id,ko,en,name,value)=>recipe(id,ko,en,`BEGIN
  DBMS_CLOUD_AI.SET_ATTRIBUTE(
    profile_name => '<PROFILE_NAME>',
    attribute_name => '${name}',
    attribute_value => '${value}');
END;
/`,'common/db/ProfileEditPolicy',{risk:'write',doc:aiDoc,verify:attributes,
  note:pair('기존 프로필의 해당 속성만 수정합니다. 프로필을 삭제·재생성하지 않습니다. 변경 전 값을 보관하고 다른 속성도 함께 비교하세요.','Changes only the named attribute of the existing profile; does not drop/recreate it. Retain the previous value and compare other attributes too.')});
const comments=`SELECT TABLE_NAME, COMMENTS
FROM SYS.ALL_TAB_COMMENTS
WHERE OWNER = '<SCHEMA>' AND TABLE_NAME = '<TABLE_NAME>';
SELECT COLUMN_NAME, COMMENTS
FROM SYS.ALL_COL_COMMENTS
WHERE OWNER = '<SCHEMA>' AND TABLE_NAME = '<TABLE_NAME>'
ORDER BY COLUMN_NAME;`;
const annotations=`SELECT COLUMN_NAME, ANNOTATION_NAME, ANNOTATION_VALUE, DOMAIN_OWNER, DOMAIN_NAME
FROM SYS.ALL_ANNOTATIONS_USAGE
WHERE ANNOTATION_OWNER = '<SCHEMA>' AND OBJECT_NAME = '<TABLE_NAME>'
  AND OBJECT_TYPE = 'TABLE'
ORDER BY COLUMN_NAME NULLS FIRST, ANNOTATION_NAME;`;
const ontologyVersions=`SELECT SEQ, OBJECT_NAME, REVISION, STATE, ACTOR, RECORDED_AT, PAYLOAD
FROM "<SCHEMA>"."DBC_ONTOLOGY_CATALOG"
WHERE OBJECT_OWNER = '<SCHEMA>' AND OBJECT_NAME = '<TABLE_NAME>'
ORDER BY REVISION DESC
FETCH FIRST 20 ROWS ONLY;`;
const ontologyLatest=`SELECT OBJECT_NAME, REVISION, STATE, PAYLOAD
FROM (
  SELECT OBJECT_NAME, REVISION, STATE, PAYLOAD,
         ROW_NUMBER() OVER (PARTITION BY OBJECT_OWNER, OBJECT_NAME ORDER BY REVISION DESC) RN
  FROM "<SCHEMA>"."DBC_ONTOLOGY_CATALOG"
  WHERE OBJECT_OWNER = '<SCHEMA>' AND OBJECT_NAME = '<TABLE_NAME>'
)
WHERE RN = 1;`;
function generate(id,ko,en,action) {
  return recipe(id,ko,en,`-- SQLcl: CLOB output; generated SQL is NOT executed.
SET LONG 1000000
VARIABLE ai_response CLOB
BEGIN
  IF DBMS_CLOUD_AI.GET_CONVERSATION_ID IS NOT NULL THEN
    RAISE_APPLICATION_ERROR(-20051, 'Active conversation is not allowed');
  END IF;
  :ai_response := DBMS_CLOUD_AI.GENERATE(
    prompt => '<QUESTION_OR_PREPARED_PROMPT>',
    profile_name => '<PROFILE_NAME>',
    action => '${action}',
    attributes => '{"conversation":false}');
END;
/
PRINT ai_response`,'repository/AiAssistantRepository',{risk:'ai',doc:aiDoc,
    note:pair('앱과 같은 GENERATE 호출입니다. 질문·선택 근거를 조합한 최종 프롬프트는 앱이 만듭니다. SHOWPROMPT는 현재 재구성이며 과거 전송 원문이 아닙니다. SQL 생성 성공은 정합성을 보장하지 않습니다.','Uses the application GENERATE call. The app assembles the final prompt from the question and selected evidence. SHOWPROMPT reconstructs current context, not a historical transmission. SQL generation does not establish correctness.')});
}
const aiHistory=recipe('history','변경 전후 JSON 이력 조회','Read before/after JSON history',`SELECT SEQ, OBJECT_TYPE, OBJECT_NAME, ATTRIBUTE_NAME, ACTOR, EVENT_AT,
       ENTRY_KIND, OUTCOME, BEFORE_JSON, AFTER_JSON, PAYLOAD_JSON
FROM "<SCHEMA>"."DBC_AI_HISTORY"
WHERE OBJECT_TYPE = '<OBJECT_TYPE>' AND OBJECT_NAME = '<OBJECT_NAME>'
ORDER BY SEQ DESC FETCH FIRST 20 ROWS ONLY;`,'common/db/AiHistorySql',{
  note:pair('이력 저장소가 설치·활성화된 범위만 조회됩니다. 앱 밖의 모든 변경이나 과거 전체값이 자동 수집되었다고 볼 수 없습니다.','Requires the installed history store. Do not assume every external change or historical full value has been captured.')});
const ontologyAppend=recipe('append','정의 저장·승인: 새 버전 추가','Save/approve: append a revision',`-- Storage step only. Bind the complete, app-validated Document JSON as CLOB.
-- <NEXT_REVISION> must equal the observed latest revision + 1.
INSERT INTO "<SCHEMA>"."DBC_ONTOLOGY_CATALOG"
  (FORMAT_VERSION, OBJECT_OWNER, OBJECT_NAME, REVISION, DOCUMENT_ID,
   STATE, ACTOR, RECORDED_AT, PAYLOAD)
VALUES (1, '<SCHEMA>', '<TABLE_NAME>', <NEXT_REVISION>, '<DOCUMENT_UUID>',
        '<DRAFT_OR_APPROVED>', SYS_CONTEXT('USERENV','SESSION_USER'),
        SYSTIMESTAMP, :document_json);`,'repository/OntologyRepository',{risk:'write',verify:ontologyVersions,
  note:pair('저장 단계의 실제 INSERT입니다. JSON은 Ontology.Document 전체 형식이어야 합니다. 기존 DOCUMENT_ID를 유지하고 최신 revision을 확인해야 합니다. 앱의 승인·민감정보·관계·동시 변경 검증을 대신하지 않으므로 단독 실행으로 승인을 재현하지 마세요. 원본 코멘트는 변경하지 않습니다.','This is the storage INSERT, not the full approval workflow. Bind a complete Ontology.Document, preserve DOCUMENT_ID and verify the latest revision. It does not reproduce approval, sensitive-data, relationship or concurrency validation. Source comments are unchanged.')});
const glossaryApproved=`SELECT OBJECT_NAME, REVISION,
       JSON_VALUE(PAYLOAD, '$.meaning.concept' RETURNING VARCHAR2(256) ERROR ON ERROR) CONCEPT,
       JSON_VALUE(PAYLOAD, '$.meaning.description' RETURNING VARCHAR2(8000) ERROR ON ERROR) DESCRIPTION,
       JSON_QUERY(PAYLOAD, '$.meaning.columns' RETURNING CLOB ERROR ON ERROR) COLUMNS_JSON,
       JSON_QUERY(PAYLOAD, '$.meaning.valueMappings' RETURNING CLOB ERROR ON ERROR) VALUE_MAPPINGS
FROM (
  SELECT OBJECT_NAME, REVISION, STATE, PAYLOAD,
         ROW_NUMBER() OVER (PARTITION BY OBJECT_OWNER, OBJECT_NAME ORDER BY REVISION DESC) RN
  FROM "<SCHEMA>"."DBC_ONTOLOGY_CATALOG"
  WHERE OBJECT_OWNER = '<SCHEMA>' AND OBJECT_NAME = '<TABLE_NAME>'
)
WHERE RN = 1 AND STATE = 'APPROVED';`;
const problemList=`SELECT RECORD_ID, STATE, ACTOR, RECORDED_AT, UPDATED_AT, PAYLOAD
FROM "<SCHEMA>"."DBC_APP_RECORD"
WHERE RECORD_TYPE = 'SELECT_AI_PROBLEM' AND STATE = 'SUCCEEDED'
ORDER BY SEQ DESC FETCH FIRST 20 ROWS ONLY;`;
const problemAttempts=`SELECT RECORD_ID, STATE, RECORDED_AT, PAYLOAD
FROM "<SCHEMA>"."DBC_APP_RECORD"
WHERE RECORD_TYPE = 'SELECT_AI_ATTEMPT' AND STATE = 'SUCCEEDED'
  AND JSON_VALUE(PAYLOAD, '$.parentId') = '<PARENT_UUID>'
ORDER BY SEQ;`;
const feedbackList=`SELECT ROWIDTOCHAR(f.ROWID) ROW_ID, f.CONTENT,
       JSON_VALUE(f.ATTRIBUTES, '$.response' RETURNING CLOB) RESPONSE_SQL,
       JSON_VALUE(f.ATTRIBUTES, '$.feedback_content' RETURNING CLOB) FEEDBACK_CONTENT,
       JSON_SERIALIZE(f.ATTRIBUTES RETURNING CLOB) ATTRIBUTES_JSON
FROM "<SCHEMA>"."<FEEDBACK_TABLE>" f
FETCH FIRST 20 ROWS ONLY;`;
export const sqlHelpCatalog={
  profiles:[
    recipe('list','프로필 목록·속성 조회','Read Profile list and attributes',profileList+'\n\n'+attributes,'repository/DatabaseRepository',{doc:aiDoc}),
    recipe('create','프로필 생성','Create a Profile',`DECLARE
  a CLOB := '{"provider":"oci","credential_name":"<CREDENTIAL_NAME>","model":"<MODEL>","object_list":[{"owner":"<SCHEMA>","name":"<TABLE_NAME>"}]}';
  d CLOB := '<DESCRIPTION>';
BEGIN
  DBMS_CLOUD_AI.CREATE_PROFILE(
    profile_name => '<NEW_PROFILE_NAME>', attributes => a,
    status => 'ENABLED', description => d);
END;
/`,'repository/AiCreationRepository',{risk:'write-ai',doc:aiDoc,verify:profileList,
      note:pair('OCI 기본 예제입니다. 리전·모델·인증 방식에 필요한 추가 속성은 현재 provider 문서와 실제 프로필을 확인하세요. 화면의 복제도 읽어 온 속성으로 새 CREATE_PROFILE을 호출하며 Feedback 복사는 별도입니다.','Minimal OCI example. Check provider-specific region/model/authentication attributes. Cloning creates a new profile from read attributes; copying Feedback is separate.')}),
    profileOption('model','모델 변경','Change model','model','<MODEL>'),
    profileOption('objects','허용 객체 목록 변경','Change allowed objects','object_list','[{"owner":"<SCHEMA>","name":"<TABLE_NAME>"}]'),
    profileOption('options','옵션·추가 지침 변경','Change options or instructions','<ATTRIBUTE_NAME>','<ATTRIBUTE_VALUE>'),
    {...aiHistory,sql:aiHistory.sql.replace('<OBJECT_TYPE>','PROFILE').replace('<OBJECT_NAME>','<PROFILE_NAME>')}
  ],
  tables:[
    recipe('list','테이블·뷰·컬럼 조회','Read tables, views and columns',`SELECT DISTINCT OBJECT_NAME FROM SYS.ALL_OBJECTS WHERE OWNER = '<SCHEMA>' AND SUBOBJECT_NAME IS NULL AND OBJECT_TYPE IN ('TABLE','VIEW') ORDER BY OBJECT_NAME;
SELECT COLUMN_ID, COLUMN_NAME, DATA_TYPE, NULLABLE
FROM SYS.ALL_TAB_COLUMNS
WHERE OWNER = '<SCHEMA>' AND TABLE_NAME = '<TABLE_NAME>' ORDER BY COLUMN_ID;
${comments}`,'repository/DatabaseRepository'),
    recipe('comment','테이블·뷰 코멘트 수정','Edit table or view comment',`COMMENT ON TABLE "<SCHEMA>"."<TABLE_NAME>" IS '<COMMENT>';`,'common/db/MetadataSql',{risk:'ddl',verify:comments,
      note:pair('일반 뷰 코멘트도 COMMENT ON TABLE을 사용합니다. TABLE_NAME 자리에 뷰 이름을 지정하세요. 뷰 Annotation 편집은 지원하지 않습니다.','Ordinary view comments also use COMMENT ON TABLE. Supply the view name as TABLE_NAME. View annotation editing is unsupported.')}),
    recipe('column-comment','컬럼 코멘트 수정','Edit column comment',`COMMENT ON COLUMN "<SCHEMA>"."<TABLE_NAME>"."<COLUMN_NAME>" IS '<COMMENT>';`,'common/db/MetadataSql',{risk:'ddl',verify:comments}),
    recipe('annotation','테이블 Annotation 추가','Add table Annotation',`ALTER TABLE "<SCHEMA>"."<TABLE_NAME>"
  ANNOTATIONS (ADD "<ANNOTATION_NAME>" '<ANNOTATION_VALUE>');`,'common/db/MetadataSql',{risk:'ddl',verify:annotations,
      note:pair('이미 있는 Annotation 수정은 ADD 대신 REPLACE를 사용합니다. Domain에서 상속된 값은 앱에서 직접 편집하지 않습니다. DB의 Annotation 지원 여부를 확인하세요.','Use REPLACE instead of ADD for an existing Annotation. The app does not directly edit inherited domain values. Check database support.')}),
    recipe('column-annotation','컬럼 Annotation 수정','Replace column Annotation',`ALTER TABLE "<SCHEMA>"."<TABLE_NAME>" MODIFY "<COLUMN_NAME>"
  ANNOTATIONS (REPLACE "<ANNOTATION_NAME>" '<ANNOTATION_VALUE>');`,'common/db/MetadataSql',{risk:'ddl',verify:annotations}),
    recipe('annotation-replace','테이블 Annotation 수정','Replace table Annotation',`ALTER TABLE "<SCHEMA>"."<TABLE_NAME>"
  ANNOTATIONS (REPLACE "<ANNOTATION_NAME>" '<ANNOTATION_VALUE>');`,'common/db/MetadataSql',{risk:'ddl',verify:annotations}),
    recipe('column-annotation-add','컬럼 Annotation 추가','Add column Annotation',`ALTER TABLE "<SCHEMA>"."<TABLE_NAME>" MODIFY "<COLUMN_NAME>"
  ANNOTATIONS (ADD "<ANNOTATION_NAME>" '<ANNOTATION_VALUE>');`,'common/db/MetadataSql',{risk:'ddl',verify:annotations}),
    recipe('history','코멘트·Annotation 변경 이력 조회','Read comment/Annotation history',`SELECT SEQ, CHANGED_AT, CHANGED_BY, COLUMN_NAME, CHANGE_KIND,
       ANNOTATION_NAME, BEFORE_JSON, AFTER_JSON
FROM "<SCHEMA>"."DBC_METADATA_HISTORY"
WHERE SCHEMA_NAME = '<SCHEMA>' AND TABLE_NAME = '<TABLE_NAME>'
ORDER BY SEQ DESC FETCH FIRST 20 ROWS ONLY;`,'repository/MetadataHistoryRepository')
  ],
  credentials:[
    recipe('list','Credential 목록 조회','Read credential metadata',`SELECT CREDENTIAL_NAME, ENABLED
FROM SYS.USER_CREDENTIALS ORDER BY CREDENTIAL_NAME;`,'repository/CredentialCatalogRepository',{
      note:pair('현재 로그인 사용자의 목록입니다. 비밀값을 조회하지 않으며 연결 성공을 확인하는 테스트도 아닙니다.','Current-user metadata only: no secrets and no connection test.')}),
    recipe('references','Credential 참조 프로필 조회','Read profiles referencing a credential',`SELECT PROFILE_NAME, ATTRIBUTE_VALUE
FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES
WHERE ATTRIBUTE_NAME = 'credential_name'
  AND DBMS_LOB.SUBSTR(ATTRIBUTE_VALUE, 4000, 1) = '<CREDENTIAL_NAME>';`,'repository/CredentialCatalogRepository'),
    recipe('api-key','OCI API 키 Credential 생성 안내','Create an OCI API-key credential',`BEGIN
  DBMS_CLOUD.CREATE_CREDENTIAL(
    credential_name => '<CREDENTIAL_NAME>',
    user_ocid => '<USER_OCID>', tenancy_ocid => '<TENANCY_OCID>',
    private_key => :private_key_pem, fingerprint => '<FINGERPRINT>');
END;
/`,'repository/CredentialCatalogRepository',{risk:'write',source:'src/main/resources/templates/fragments/select-ai-setup.html',doc:'https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/dbms-cloud-subprograms.html',
      note:pair('화면의 시작 안내에 대응하는 수동 설정입니다(목록 화면은 생성 API를 실행하지 않습니다). 공개키를 OCI 사용자에 먼저 등록하고 대응하는 개인키를 안전한 CLOB 바인드로 전달하세요. 키를 화면·로그·소스에 저장하지 마세요.','Manual setup from the setup guide, not a catalog-screen API call. Register the public key with the OCI user first, supply its private key through a secure CLOB bind, and never store it in UI/logs/source.')}),
    recipe('resource-principal','Resource Principal 활성화 안내','Enable Resource Principal',`-- Run as ADMIN, after configuring the OCI dynamic group and IAM policy.
BEGIN
  DBMS_CLOUD_ADMIN.ENABLE_RESOURCE_PRINCIPAL(username => '<DB_USER>');
END;
/
SELECT OWNER, CREDENTIAL_NAME FROM DBA_CREDENTIALS
WHERE OWNER = '<DB_USER>' AND CREDENTIAL_NAME = 'OCI$RESOURCE_PRINCIPAL';`,'repository/CredentialCatalogRepository',{risk:'write',source:'src/main/resources/templates/fragments/select-ai-setup.html',doc:'https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/resource-principal.html',
      note:pair('DB 설정 외에 OCI Dynamic Group 및 IAM 정책이 필요합니다. 이 화면의 수동 시작 안내이며 앱이 자동 활성화하지 않습니다.','Also requires OCI dynamic group and IAM policy. Manual setup guide only; the app does not enable it automatically.')})
  ],
  'ai-test':[generate('showsql','SQL 만들기','Generate SQL','showsql'),generate('showprompt','전체 프롬프트 보기','View reconstructed prompt','showprompt'),generate('chat','AI 검토·설명','AI review or explanation','chat')],
  feedback:[
    recipe('list','Feedback 원문·응답 SQL 조회','Read Feedback and response SQL',feedbackList,'repository/AiFeedbackRepository',{
      note:pair('<FEEDBACK_TABLE>은 실제 존재·권한을 확인한 프로필의 Feedback 테이블명입니다. 후보명만으로 존재를 단정하지 않습니다. 저장된 행이 현재 프롬프트에 사용되었다는 증거는 아닙니다.','Use the verified Feedback table, not an assumed candidate name. A stored row does not prove inclusion in the current prompt.')}),
    recipe('add','Feedback 등록·수정','Add or update Feedback',`DECLARE
  q CLOB := '<SELECT_AI_ORIGINAL_TEXT>';
  r CLOB := '<CORRECTED_RESPONSE_SQL>';
  c CLOB := '<FEEDBACK_EXPLANATION>';
BEGIN
  DBMS_CLOUD_AI.FEEDBACK(profile_name => '<PROFILE_NAME>',
    sql_text => q, feedback_type => 'negative', response => r,
    feedback_content => c, operation => 'add');
END;
/`,'repository/AiCreationRepository',{risk:'write-ai',doc:aiDoc,verify:feedbackList,
      note:pair('앱의 등록과 수정 모두 operation=add를 호출합니다. 수정 시 원문 키를 고정하고 기존값·프로필 변경 여부를 먼저 검사합니다. 이 검사와 이력 기록은 별도이며, 같은 프로필의 다른 질문에도 영향을 줄 수 있습니다.','The app uses operation=add for both creation and editing. Editing preserves the original-text key and first checks existing values/profile changes. These checks and history capture are separate; other questions using this profile may be affected.')}),
    {...aiHistory,sql:aiHistory.sql.replace('<OBJECT_TYPE>','FEEDBACK').replace('<OBJECT_NAME>','<PROFILE_NAME>')}
  ],
  agents:[
    recipe('list','Team·Agent·Task·Tool 목록 조회','Read Team, Agent, Task and Tool catalogs',`SELECT * FROM USER_AI_AGENT_TEAMS FETCH FIRST 100 ROWS ONLY;
SELECT * FROM USER_AI_AGENTS FETCH FIRST 100 ROWS ONLY;
SELECT * FROM USER_AI_AGENT_TASKS FETCH FIRST 100 ROWS ONLY;
SELECT * FROM USER_AI_AGENT_TOOLS FETCH FIRST 100 ROWS ONLY;`,'repository/AgentCatalogRepository',{
      note:pair('버전별 컬럼 차이 때문에 앱은 먼저 메타데이터를 확인해 표시 컬럼을 선택합니다. 예제의 *는 해당 카탈로그 구조 확인용입니다.','The app discovers catalog columns before selecting them because versions differ; * here inspects that catalog structure.')}),
    ...['TEAM','AGENT','TASK','TOOL'].map(kind=>recipe(`create-${kind.toLowerCase()}`,`${kind} 생성`, `Create ${kind}`,`-- Supply the JSON attributes reviewed in the creation preview as a CLOB bind.
BEGIN
  DBMS_CLOUD_AI_AGENT.CREATE_${kind}(
    ${kind.toLowerCase()}_name => '<OBJECT_NAME>', attributes => :attributes_json,
    status => 'ENABLED', description => '<DESCRIPTION>');
END;
/`,'repository/AiCreationRepository',{risk:'write',note:pair('생성 미리보기에서 검토한 객체 종류별 JSON을 바인드합니다. 연결된 프로필·Agent·Task·Tool의 존재 및 API 인자는 앱에서 별도 확인합니다. 실행(RUN_TEAM) 예제가 아닙니다.','Bind the kind-specific JSON reviewed in creation preview. Referenced objects and API signatures are validated separately by the app. This does not run a team.')})),
    recipe('edit','객체 속성 수정','Edit object attributes',`BEGIN
  DBMS_CLOUD_AI_AGENT.SET_ATTRIBUTE(
    object_name => '<OBJECT_NAME>', object_type => '<TEAM_AGENT_TASK_OR_TOOL>',
    attribute_name => '<ATTRIBUTE_NAME>', attribute_value => '<ATTRIBUTE_VALUE>');
END;
/`,'repository/AgentObjectEditRepository',{risk:'write'}),aiHistory
  ],
  executions:[
    recipe('conversations','대화 이력 조회','Read conversation prompts',`SELECT CONVERSATION_PROMPT_ID, CREATED, PROFILE_NAME, PROMPT_ACTION,
       PROMPT, PROMPT_RESPONSE, CLIENT_IDENTIFIER
FROM USER_CLOUD_AI_CONVERSATION_PROMPTS
WHERE CREATED >= TO_TIMESTAMP_TZ('<FROM_ISO_OFFSET>', 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM')
  AND CREATED < TO_TIMESTAMP_TZ('<TO_EXCLUSIVE_ISO_OFFSET>', 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM')
ORDER BY CREATED DESC FETCH FIRST 20 ROWS ONLY;`,'repository/AiExecutionHistoryRepository'),
    recipe('mapping','Select AI SQL 매핑 조회','Read Select AI SQL mappings',`SELECT SQL_ID, MAPPED_SQL_ID, TRANSLATION_TIMESTAMP, SQL_FULLTEXT, MAPPED_SQL_FULLTEXT
FROM SYS.V_$MAPPED_SQL
WHERE REGEXP_LIKE(SQL_TEXT, '^[[:space:]]*select[[:space:]]+ai([[:space:]]|$)', 'i')
ORDER BY TRANSLATION_TIMESTAMP DESC NULLS LAST FETCH FIRST 20 ROWS ONLY;`,'repository/AiMappedSqlRepository'),
    recipe('cache','공유 SQL 조회','Read shared SQL',`SELECT SQL_ID, CHILD_NUMBER, CON_ID, PARSING_SCHEMA_NAME, LAST_ACTIVE_TIME,
       EXECUTIONS, SQL_FULLTEXT
FROM SYS.V_$SQL
WHERE PARSING_SCHEMA_NAME = '<SCHEMA>'
  AND LAST_ACTIVE_TIME >= TO_TIMESTAMP('<FROM_DATE>', 'YYYY-MM-DD')
  AND LAST_ACTIVE_TIME < TO_TIMESTAMP('<TO_EXCLUSIVE_DATE>', 'YYYY-MM-DD')
  AND (REGEXP_LIKE(SQL_FULLTEXT, '^[[:space:]]*select[[:space:]]+ai([[:space:]]|$)', 'i')
       OR REGEXP_LIKE(SQL_FULLTEXT, 'DBMS_CLOUD_AI"?[[:space:]]*[.][[:space:]]*"?GENERATE', 'i'))
ORDER BY LAST_ACTIVE_TIME DESC FETCH FIRST 20 ROWS ONLY;`,'repository/AiSqlHistoryRepository',{
      note:pair('공유 풀에 남은 SQL 텍스트 기준 후보입니다. 모든 실행의 영구 이력 또는 생성 결과 저장소가 아닙니다.','Text-matched candidates still in the shared pool, not a permanent record of every execution or generated result.')}),
    recipe('awr','AWR SQL 원문 확인','Read AWR SQL text',`SELECT DBID, SQL_ID, CON_DBID, CON_ID, SQL_TEXT
FROM SYS.DBA_HIST_SQLTEXT
WHERE DBID = <DBID> AND SQL_ID = '<SQL_ID>'
  AND NVL(CON_DBID, -1) = <CON_DBID_OR_MINUS_ONE>
  AND NVL(CON_ID, -1) = <CON_ID_OR_MINUS_ONE>;`,'repository/AiSqlHistoryRepository',{
      note:pair('AWR 조회 권한과 사용 계약·라이선스를 먼저 확인하세요. 목록에서 확인한 DB/container 식별자까지 지정합니다.','Confirm AWR access and applicable licensing first. Use the database/container identifiers from the selected list item.')}),
    recipe('audit','Select AI 감사 로그 조회','Read Select AI audit events',`SELECT EVENT_TIMESTAMP_UTC, DBUSERNAME, ACTION_NAME, RETURN_CODE, SQL_TEXT
FROM UNIFIED_AUDIT_TRAIL
WHERE DBUSERNAME = '<SCHEMA>' AND OBJECT_NAME = 'DBMS_CLOUD_AI'
  AND EVENT_TIMESTAMP_UTC >= TO_TIMESTAMP('<FROM_DATE>', 'YYYY-MM-DD')
  AND EVENT_TIMESTAMP_UTC < TO_TIMESTAMP('<TO_EXCLUSIVE_DATE>', 'YYYY-MM-DD')
ORDER BY EVENT_TIMESTAMP_UTC DESC FETCH FIRST 20 ROWS ONLY;`,'repository/AiSqlHistoryRepository',{
      note:pair('이 예제는 패키지 감사 이벤트 조회입니다. 앱은 PUBLIC synonym이 SYS/AUDSYS의 로컬 감사 뷰인지 확인합니다. 감사 정책·보존 범위 밖의 실행은 나타나지 않습니다.','Package audit events only. The app resolves the local SYS/AUDSYS public synonym first. Events outside enabled audit policy/retention will not appear.')}),
    recipe('agent-runs','Agent 실행·Task 이력 조회','Read Agent and Task runs',`SELECT * FROM USER_AI_AGENT_TEAM_HISTORY
WHERE TEAM_EXEC_ID = '<TEAM_EXEC_ID>';
SELECT * FROM USER_AI_AGENT_TASK_HISTORY
WHERE TEAM_EXEC_ID = '<TEAM_EXEC_ID>';`,'repository/AiAgentExecutionRepository')
  ],
  scheduler:[
    recipe('jobs','Scheduler 작업·실행 일정 조회','Read Scheduler jobs and schedules',`SELECT JOB_NAME, ENABLED, STATE, JOB_TYPE, JOB_ACTION,
       REPEAT_INTERVAL, LAST_START_DATE, NEXT_RUN_DATE
FROM SYS.USER_SCHEDULER_JOBS ORDER BY JOB_NAME;`,'repository/SchedulerRepository'),
    recipe('runs','작업 실행 결과·오류 조회','Read job run results and errors',`SELECT LOG_ID, LOG_DATE, JOB_NAME, STATUS, ERROR#, ACTUAL_START_DATE,
       RUN_DURATION, ADDITIONAL_INFO
FROM SYS.USER_SCHEDULER_JOB_RUN_DETAILS
WHERE JOB_NAME = '<JOB_NAME>' ORDER BY LOG_ID DESC FETCH FIRST 20 ROWS ONLY;`,'repository/SchedulerRepository')
  ],
  external:[
    recipe('tables','외부 테이블·파일 위치 조회','Read external tables and locations',`SELECT TABLE_NAME, TYPE_NAME FROM SYS.USER_EXTERNAL_TABLES ORDER BY TABLE_NAME;
SELECT TABLE_NAME, DIRECTORY_NAME, LOCATION FROM SYS.USER_EXTERNAL_LOCATIONS
WHERE TABLE_NAME = '<TABLE_NAME>';`,'repository/ExternalSourcesRepository'),
    recipe('links','DB Link 목록 조회','Read database links',`SELECT DB_LINK, USERNAME, HOST, CREATED FROM SYS.USER_DB_LINKS ORDER BY DB_LINK;`,'repository/ExternalSourcesRepository'),
    recipe('acl','호스트 접근 권한 조회','Read host access privileges',`SELECT HOST, LOWER_PORT, UPPER_PORT, PRIVILEGE
FROM SYS.USER_HOST_ACES ORDER BY HOST, LOWER_PORT, UPPER_PORT;`,'repository/ExternalSourcesRepository')
  ],
  security:[
    recipe('roles','Data Role 조회','Read Data Roles',`SELECT * FROM SYS.DBA_DATA_ROLES ORDER BY DATA_ROLE;`,'repository/DeepDataSecurityRepository'),
    recipe('assignments','Data Role 부여 조회','Read Data Role assignments',`SELECT * FROM SYS.DBA_DATA_ROLE_GRANTS
ORDER BY DATA_ROLE, GRANTEE_TYPE, GRANTEE, ROLE_TYPE, START_TIME, END_TIME;`,'repository/DeepDataSecurityRepository'),
    recipe('grants','Data Grant 조회','Read Data Grants',`SELECT * FROM SYS.DBA_DATA_GRANTS
WHERE OBJECT_OWNER = '<SCHEMA>' ORDER BY OWNER, GRANT_NAME, OBJECT_NAME;`,'repository/DeepDataSecurityRepository',{
      note:pair('DBA 뷰 접근이 없으면 ALL_DATA_GRANTS로 보이는 범위만 확인할 수 있습니다. 권한 부여나 최종 사용자 컨텍스트 설정은 수행하지 않습니다.','Without DBA visibility, ALL_DATA_GRANTS exposes only visible grants. This neither grants privileges nor attaches end-user context.')}),
    recipe('applications','애플리케이션 매핑 조회','Read application identities',`SELECT * FROM SYS.DBA_APPLICATION_IDENTITIES ORDER BY APPLICATION_NAME;`,'repository/DeepDataSecurityRepository')
  ],
  functions:[
    recipe('list','함수 목록 조회','Read functions',`SELECT DISTINCT p.OBJECT_NAME, p.PROCEDURE_NAME
FROM SYS.ALL_PROCEDURES p
WHERE p.OWNER = '<SCHEMA>' AND (
  (p.OBJECT_TYPE = 'FUNCTION' AND p.PROCEDURE_NAME IS NULL) OR
  (p.OBJECT_TYPE = 'PACKAGE' AND p.PROCEDURE_NAME IS NOT NULL AND EXISTS (
    SELECT 1 FROM SYS.ALL_ARGUMENTS a
    WHERE a.OWNER = p.OWNER AND a.OBJECT_ID = p.OBJECT_ID
      AND a.SUBPROGRAM_ID = p.SUBPROGRAM_ID AND a.POSITION = 0 AND a.DATA_LEVEL = 0)))
ORDER BY p.OBJECT_NAME, p.PROCEDURE_NAME;`,'repository/FunctionCatalogRepository'),
    recipe('arguments','패키지 함수 인자 확인','Read packaged-function arguments',`SELECT SUBPROGRAM_ID, OVERLOAD, POSITION, ARGUMENT_NAME, DATA_TYPE, IN_OUT, DEFAULTED
FROM SYS.ALL_ARGUMENTS
WHERE OWNER = '<SCHEMA>' AND PACKAGE_NAME = '<PACKAGE_NAME>'
  AND OBJECT_NAME = '<FUNCTION_NAME>' AND DATA_LEVEL = 0
ORDER BY SUBPROGRAM_ID, SEQUENCE;`,'repository/FunctionCatalogRepository'),
    recipe('source','함수·패키지 소스 조회','Read routine source',`SELECT LINE, TEXT FROM SYS.ALL_SOURCE
WHERE OWNER = '<SCHEMA>' AND NAME = '<OBJECT_NAME>' AND TYPE = '<OBJECT_TYPE>'
ORDER BY LINE;`,'repository/RoutineSourceRepository')
  ],
  vectors:[
    recipe('columns','VECTOR 컬럼·임베딩 모델 조회','Read VECTOR columns and embedding models',`SELECT TABLE_NAME, COLUMN_NAME FROM SYS.ALL_TAB_COLUMNS
WHERE OWNER = '<SCHEMA>' AND DATA_TYPE = 'VECTOR' ORDER BY TABLE_NAME, COLUMN_NAME;
SELECT OWNER, MODEL_NAME FROM SYS.ALL_MINING_MODELS
WHERE MINING_FUNCTION = 'EMBEDDING' AND ALGORITHM = 'ONNX' ORDER BY OWNER, MODEL_NAME;`,'repository/VectorSearchRepository'),
    recipe('embedding','DB 내 모델로 검색 벡터 생성','Create an embedding with an in-database model',`SELECT VECTOR_SERIALIZE(VECTOR_EMBEDDING("<MODEL_OWNER>"."<MODEL_NAME>"
  USING '<SEARCH_TEXT>' AS DATA) RETURNING CLOB) QUERY_VECTOR
FROM SYS.DUAL;`,'repository/VectorSearchRepository',{
      note:pair('설치된 DB 내 ONNX 모델 예제입니다. 이 모델의 차원·거리 척도를 대상 VECTOR 컬럼과 맞추세요. 외부 임베딩 모델 경로는 별도 외부 호출·비용이 있습니다.','Uses an installed in-database ONNX model. Match dimensions/metric to the target vector. External embedding is a separate billed path.')}),
    recipe('search','벡터 유사도 검색','Search vector similarity',`SELECT ROWIDTOCHAR(t.ROWID) ROW_ID, t."<CONTENT_COLUMN>",
       VECTOR_DISTANCE(t."<VECTOR_COLUMN>", TO_VECTOR(:query_vector_json), COSINE) DBC_DISTANCE
FROM "<SCHEMA>"."<TABLE_NAME>" t
WHERE t."<VECTOR_COLUMN>" IS NOT NULL
ORDER BY DBC_DISTANCE, t.ROWID FETCH EXACT FIRST 10 ROWS ONLY;`,'repository/VectorSearchRepository')
  ],
  ontology:[
    recipe('current','저장된 업무 정의·관계 조회','Read saved business definitions and relationships',ontologyLatest,'repository/OntologyRepository',{
      note:pair('PAYLOAD에 원본 메타데이터와 업무 정의·컬럼·관계가 저장됩니다. 문서 구조 해석과 화면 그래프 배치는 Java/브라우저에서 수행합니다.','PAYLOAD stores source metadata, business definitions, columns and relationships; Java/browser code interprets the document and lays out the graph.')}),
    recipe('history','정의 버전·이력 조회','Read definition revisions',ontologyVersions,'repository/OntologyRepository'),ontologyAppend,
    recipe('metadata','원본 코멘트·Annotation 확인','Read original comments and Annotations',comments+'\n\n'+annotations,'repository/DatabaseRepository')
  ],
  'ontology-terms':[
    recipe('approved','승인 용어·별칭 검색의 원천 조회','Read approved glossary-search sources',glossaryApproved,'repository/OntologyRepository',{
      note:pair('최신 버전이 APPROVED인 정의만 조회합니다. 정확명·등록 별칭 매칭은 이 결과를 받아 앱에서 수행합니다. 오래된 승인 버전을 최신 초안 대신 사용하지 않습니다.','Only definitions whose latest revision is APPROVED. Exact-name/registered-alias matching is performed by the app on this result, never by substituting an older approval for a newer draft.')}),
    recipe('text','Oracle Text 검색','Search using Oracle Text',`SELECT TERM_ID, SOURCE_TABLE, SOURCE_REVISION, TERM_TEXT, DEFINITION_TEXT, SCORE(1) SCORE
FROM "<SCHEMA>"."DBC_GLOSSARY_TERM"
WHERE SOURCE_OWNER = '<SCHEMA>' AND PROFILE_REF = '<PROFILE_NAME>'
  AND SOURCE_TABLE = '<TABLE_NAME>' AND SOURCE_REVISION = <APPROVED_REVISION>
  AND CONTAINS(SEARCH_TEXT, :escaped_contains_expression, 1) > 0
ORDER BY SCORE(1) DESC FETCH FIRST 20 ROWS ONLY;`,'common/db/OracleTextSql',{
      note:pair('승인 용어 캐시와 CONTEXT 인덱스가 설치·동기화되어 있어야 합니다. 앱은 최신 승인 revision과 profile을 재검증하고 검색어의 Oracle Text 특수문자를 이스케이프합니다. 원문을 그대로 표현식에 넣지 마세요.','Requires synchronized approved-term cache and CONTEXT index. The app revalidates revision/profile and escapes Oracle Text operators; do not pass raw user text as the expression.')}),
    recipe('lexer','한국어 형태소 Lexer 설정','Configure the Korean morphology lexer',`BEGIN
  CTX_DDL.CREATE_PREFERENCE('DBC_GLT_KO_LEXER', 'KOREAN_MORPH_LEXER');
  CTX_DDL.SET_ATTRIBUTE('DBC_GLT_KO_LEXER', 'COMPOSITE', 'COMPONENT_WORD');
END;
/
CREATE INDEX "<SCHEMA>"."DBC_GLT_CTX"
ON "<SCHEMA>"."DBC_GLOSSARY_TERM"(SEARCH_TEXT)
INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('LEXER DBC_GLT_KO_LEXER');`,'common/db/OracleTextSql',{risk:'ddl',
      note:pair('전체 설치 중 Lexer·인덱스 단계입니다. 대상 테이블 생성 DDL은 이 화면의 ‘Text 설치 미리보기’에서 확인하세요. 이미 설치된 객체에 재실행하지 마세요.','Lexer/index stage of installation. Use this screen’s Text installation preview for the complete table DDL. Do not rerun on existing objects.')})
  ],
  'ontology-drift':[
    recipe('compare','저장 기준과 현재 메타데이터 조회','Read saved baseline and current metadata',ontologyLatest+'\n\n'+comments+'\n\n'+annotations,'repository/OntologyRepository',{
      note:pair('이 SQL은 비교할 두 원천을 읽습니다. 필드별 차이·관측 토큰·동시 변경 검증은 앱에서 수행하며, 수용 시 원본을 고치지 않고 새 정의 버전을 저장합니다.','Reads both comparison sources. Field-level diff, observation tokens and concurrency checks are application logic. Acceptance saves a new definition revision without modifying source metadata.')}),ontologyAppend
  ],
  'ontology-query':[
    recipe('analysis-setup','질문 형태소 분석 설정 (테이블·인덱스 불필요)','Configure question morphology (no table or index)',`-- Run as the DB Companion login user, not an unrelated selected schema.
-- Inspect existing settings first. Do not rerun over existing/partial objects.
SELECT PRE_NAME, PRE_OBJECT FROM CTXSYS.CTX_USER_PREFERENCES
WHERE PRE_NAME = 'DBC_QA_KO_LEXER';
SELECT IDX_NAME FROM CTXSYS.CTX_USER_INDEXES
WHERE IDX_NAME IN ('DBC_QA_KO_POLICY', 'DBC_BT_KO_POLICY');

-- Run this block only when DBC_QA_KO_LEXER and DBC_QA_KO_POLICY do not exist.
BEGIN
  CTX_DDL.CREATE_PREFERENCE('DBC_QA_KO_LEXER','KOREAN_MORPH_LEXER');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','MORPHEME','TRUE');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','VERB_ADJECTIVE','FALSE');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','NUMBER','FALSE');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','STOP_DIC','FALSE');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','ONE_CHAR_WORD','FALSE');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','COMPOSITE','COMPONENT_WORD');
  CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','ENGLISH','TRUE');
  CTX_DDL.CREATE_POLICY('DBC_QA_KO_POLICY',lexer=>'DBC_QA_KO_LEXER',stoplist=>'CTXSYS.EMPTY_STOPLIST');
END;
/`,'common/db/QuestionAnalysisSql',{risk:'ddl',doc:'https://docs.oracle.com/en/database/oracle/oracle-database/26/ccref/policy_tokens.html',
      verify:`SET SERVEROUTPUT ON
DECLARE
  tokens CTX_DOC.TOKEN_TAB;
  n BINARY_INTEGER;
BEGIN
  CTX_DOC.POLICY_TOKENS('DBC_QA_KO_POLICY','<QUESTION>',tokens,format=>'TEXT');
  n := tokens.FIRST;
  WHILE n IS NOT NULL LOOP
    DBMS_OUTPUT.PUT_LINE(tokens(n).token);
    n := tokens.NEXT(n);
  END LOOP;
END;
/`,
      note:pair('로그인 계정에서 CTX_DDL·CTX_DOC 실행 권한이 필요합니다. 질문 분석 전용 lexer와 policy만 생성합니다. 용어 테이블·검색 인덱스·업무 데이터는 변경하지 않습니다. 이미 유효한 DBC_BT_KO_POLICY가 있으면 호환 사용하므로 추가 설정이 필수는 아닙니다. 앱은 검색 시 자동 설치하지 않습니다. 일부 실패 시 객체 상태를 확인하고 재실행 여부를 판단하세요. 설정 후 같은 질문으로 다시 검색하세요.','Requires CTX_DDL and CTX_DOC execution privileges for the login user. Creates only a question-analysis lexer and policy; no tables, search indexes or business-data changes. An existing valid DBC_BT_KO_POLICY is supported for compatibility, so extra setup is not mandatory. Searches never install settings. Inspect partial failures before retrying. Search the same question again after setup.')}),
    recipe('definitions','동작 구조 · 저장된 정의와 관계 조회','Workflow · read saved definitions and relationships',`-- 선택 경로의 시작·중간·끝 테이블 이름으로 바꾸세요.
-- 저장 JSON 조회이며 SPARQL 실행이나 실제 업무 행 조회가 아닙니다.
SELECT OBJECT_NAME, REVISION, STATE,
       JSON_VALUE(PAYLOAD, '$.meaning.concept'
         RETURNING VARCHAR2(256) ERROR ON ERROR) CONCEPT,
       JSON_QUERY(PAYLOAD, '$.meaning.columns'
         RETURNING CLOB ERROR ON ERROR) COLUMN_DEFINITIONS,
       JSON_QUERY(PAYLOAD, '$.source.keys'
         RETURNING CLOB ERROR ON ERROR) SAVED_KEYS,
       JSON_QUERY(PAYLOAD, '$.meaning.relations'
         RETURNING CLOB ERROR ON ERROR) FK_DESCRIPTIONS,
       JSON_QUERY(PAYLOAD, '$.links'
         RETURNING CLOB ERROR ON ERROR NULL ON EMPTY) REVIEWED_LINKS
FROM (
  SELECT OBJECT_NAME, REVISION, STATE, PAYLOAD,
         ROW_NUMBER() OVER (
           PARTITION BY OBJECT_OWNER, OBJECT_NAME ORDER BY REVISION DESC
         ) RN
  FROM "<SCHEMA>"."DBC_ONTOLOGY_CATALOG"
  WHERE OBJECT_OWNER = '<SCHEMA>'
    AND OBJECT_NAME IN ('<SOURCE_TABLE>', '<LINK_TABLE>', '<TARGET_TABLE>')
)
WHERE RN = 1
ORDER BY OBJECT_NAME;`,'service/OntologyInquiry',{
      doc:'https://docs.oracle.com/en/database/oracle/oracle-database/26/adjsn/sql-json-function-json_query.html',
      purpose:pair('① Oracle Text로 질문을 분석합니다. ② 앱이 저장된 온톨로지의 명칭·정의를 찾아 승인된 관계와 검증된 FK에서 경로를 탐색합니다. ③ 선택한 테이블·관계만 RDF/JSON 근거로 AI에 전달합니다. ④ 생성 SQL을 검토하고 별도로 실행하면 실제 업무 데이터를 조회합니다. RDF 저장소에 SPARQL을 직접 실행하는 구조가 아닙니다. 아래 작업 선택에서 각 단계의 SQL/PLSQL 예제를 확인할 수 있습니다.','1. Oracle Text analyzes the question. 2. The app matches saved names/definitions and traverses approved relationships and validated FKs. 3. Only the selected tables/relationships are assembled as RDF/JSON evidence for AI. 4. Separately reviewing and executing generated SQL retrieves business rows. This is not a direct SPARQL query against an RDF store. Choose an operation below for each SQL/PLSQL example.'),
      note:pair('실제 저장 원천은 DBC_ONTOLOGY_CATALOG.PAYLOAD의 JSON입니다. source.keys는 저장 당시 FK 등 키 정보이며 meaning.relations는 FK 설명, links는 사용자가 검토한 관계 매핑입니다. 최신 버전이 초안이면 과거 승인본으로 대체하지 않습니다. 초안 명칭은 검색에 사용하지만 AI 업무 정의 근거에서는 제외합니다. FK는 ENABLED·VALIDATED, 저장 관계는 APPROVED 및 최신 정의와의 유효성 등을 앱에서 확인합니다. 검색 개념 3개 중 서로 다른 2개가 확인된 관계로 연결되면 부분 후보를 표시하고 미포함 개념을 알립니다. 이 SQL은 원천 조회이며 앱의 명칭 매칭·최대 3단계 경로 탐색·민감 컬럼 제외·RDF 조립을 재현하지 않습니다.','The source is JSON in DBC_ONTOLOGY_CATALOG.PAYLOAD. source.keys contains captured keys including FKs; meaning.relations contains FK descriptions; links contains reviewed mappings. A newer draft is not replaced with an older approval. Draft names may help retrieval but are excluded from AI business-definition evidence. The app checks ENABLED/VALIDATED FKs and APPROVED mappings, including definition freshness. Two distinct concepts connected on different entities can form a partial candidate out of three; uncovered concepts are displayed. This SQL reads sources only, not name matching, bounded three-edge traversal, sensitive-column filtering or RDF assembly.')}),
    recipe('analysis-tokens','질문의 Oracle 형태소 분석 결과 확인','Analyze question tokens',`SET SERVEROUTPUT ON
DECLARE
  tokens CTX_DOC.TOKEN_TAB;
  n BINARY_INTEGER;
BEGIN
  -- 화면에 표시된 정책명과 질문으로 바꾸세요. 정책을 새로 만들지 않습니다.
  CTX_DOC.POLICY_TOKENS(
    policy_name => '<POLICY_NAME>',
    document => '<QUESTION>',
    restab => tokens,
    format => 'TEXT');
  n := tokens.FIRST;
  WHILE n IS NOT NULL LOOP
    DBMS_OUTPUT.PUT_LINE(tokens(n).token);
    n := tokens.NEXT(n);
  END LOOP;
END;
/`,'common/db/QuestionAnalysisSql',{
      doc:'https://docs.oracle.com/en/database/oracle/oracle-database/26/ccref/policy_tokens.html',
      note:pair('질문 분석 정책을 소유한 로그인 계정에서 실행합니다. 화면에 표시된 DBC_QA_KO_POLICY 또는 호환 DBC_BT_KO_POLICY 등 실제 정책명을 사용하세요. 다른 언어는 해당 언어 정책을 선택합니다. 용어 테이블이나 CONTEXT 인덱스 없이 토큰을 출력하며, 이것만으로 테이블·관계 검색이나 AI 호출을 수행하지는 않습니다. CTX_DOC 실행 권한이 필요합니다.','Run as the login user owning the analysis policy, using the actual policy displayed by the screen, such as DBC_QA_KO_POLICY or compatible DBC_BT_KO_POLICY. Use the corresponding policy for another language. This prints tokens without a glossary table or CONTEXT index; it does not itself search relationships or call AI. CTX_DOC execution privilege is required.')}),
    recipe('fk-columns','현재 DB의 FK 연결 컬럼 확인','Inspect current FK column mappings',`SELECT fk.TABLE_NAME AS SOURCE_TABLE,
       fk.CONSTRAINT_NAME,
       fc.POSITION AS COLUMN_POSITION,
       fc.COLUMN_NAME AS SOURCE_COLUMN,
       pk.OWNER AS TARGET_SCHEMA,
       pk.TABLE_NAME AS TARGET_TABLE,
       pc.COLUMN_NAME AS TARGET_COLUMN,
       fk.STATUS, fk.VALIDATED
FROM SYS.ALL_CONSTRAINTS fk
JOIN SYS.ALL_CONS_COLUMNS fc
  ON fc.OWNER = fk.OWNER
 AND fc.CONSTRAINT_NAME = fk.CONSTRAINT_NAME
 AND fc.TABLE_NAME = fk.TABLE_NAME
JOIN SYS.ALL_CONSTRAINTS pk
  ON pk.OWNER = fk.R_OWNER
 AND pk.CONSTRAINT_NAME = fk.R_CONSTRAINT_NAME
JOIN SYS.ALL_CONS_COLUMNS pc
  ON pc.OWNER = pk.OWNER
 AND pc.CONSTRAINT_NAME = pk.CONSTRAINT_NAME
 AND pc.TABLE_NAME = pk.TABLE_NAME
 AND pc.POSITION = fc.POSITION
WHERE fk.OWNER = '<SCHEMA>'
  AND fk.TABLE_NAME IN ('<SOURCE_TABLE>', '<LINK_TABLE>', '<TARGET_TABLE>')
  AND fk.CONSTRAINT_TYPE = 'R'
  AND fk.STATUS = 'ENABLED'
  AND fk.VALIDATED = 'VALIDATED'
ORDER BY fk.TABLE_NAME, fk.CONSTRAINT_NAME, fc.POSITION;`,'repository/TableStructureRepository',{
      doc:'https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/ALL_CONS_COLUMNS.html',
      note:pair('현재 DB의 FK 확인용 SQL입니다. 온톨로지의 저장 스냅샷과 다를 수 있습니다. 복합키는 POSITION이 같은 컬럼끼리 연결하며 모든 쌍을 JOIN 조건에 포함해야 합니다. FK가 없는 승인 관계는 이 조회에 나오지 않으므로 저장된 links도 확인하세요. FK 존재는 DDS 권한 설정이나 실제 연결 데이터의 조회 결과를 뜻하지 않습니다.','This checks current DB FKs, which may differ from the saved ontology snapshot. Pair composite-key columns by POSITION and include every pair in the JOIN. Reviewed non-FK relationships are not returned here; inspect saved links separately. An FK does not establish DDS authorization or actual query results.')}),
    recipe('join-rows','선택한 관계로 실제 데이터 조회 · JOIN 예제','Query business rows using the selected relationship',`-- 시작 테이블 ← 중간 매핑 테이블 → 대상 테이블의 예입니다.
-- 테이블명과 키 컬럼을 선택 경로의 실제 매핑으로 바꾸고 실행하세요.
SELECT s."<SOURCE_KEY>" AS SOURCE_ID,
       t."<TARGET_KEY>" AS TARGET_ID
FROM "<SCHEMA>"."<SOURCE_TABLE>" s
INNER JOIN "<SCHEMA>"."<LINK_TABLE>" m
  ON s."<SOURCE_KEY>" = m."<LINK_SOURCE_KEY>"
INNER JOIN "<SCHEMA>"."<TARGET_TABLE>" t
  ON m."<LINK_TARGET_KEY>" = t."<TARGET_KEY>"
ORDER BY s."<SOURCE_KEY>", t."<TARGET_KEY>"
FETCH FIRST 200 ROWS ONLY;`,'repository/OntologyQueryRepository',{
      note:pair('예: 사용자 테이블의 사용자 키 = 권한 매핑 테이블의 사용자 키, 권한 매핑 테이블의 권역 키 = 권역 테이블의 권역 키입니다. 앱이 조회하는 구조를 수동 SELECT로 표현한 예제이며 모든 경로가 이 세 테이블 형태인 것은 아닙니다. INNER JOIN은 연결이 없는 행을 제외하며 중복 매핑이 있으면 같은 쌍이 여러 행일 수 있습니다. NULL로 전역 권한을 표현하는 등 별도 규칙은 FK만으로 추론하지 않습니다. 같은 계정이라도 서비스·세션 컨텍스트와 DDS/VPD 정책에 따라 결과가 달라질 수 있습니다. 200행은 예제 표시 상한이며 전체 건수나 정답을 뜻하지 않습니다. DB 권한·정책은 그대로 적용되지만 SQLcl 직접 실행은 앱의 확인 절차·시간 제한·이력 저장을 대신하지 않습니다.','For example, join a user key to the entitlement user key, then the entitlement region key to the region key. This manual SELECT illustrates row retrieval; not every path has three tables. INNER JOIN omits unlinked rows; duplicate mappings can produce duplicate pairs. FKs do not imply special rules such as NULL meaning global access. Service/session context and DDS/VPD policies can change results even for the same account. The 200-row cap is not a total count or proof of correctness. Database privileges/policies still apply; SQLcl does not reproduce app confirmation, timeout or history handling.')}),
    recipe('generate-sql','선택한 RDF/JSON 근거로 SQL 생성 · 실행은 별도','Generate SQL from selected RDF/JSON evidence',`-- SQLcl: AI 호출 시 외부 전송·사용량이 발생할 수 있습니다.
-- 아래 두 CLOB을 채우지 않으면 호출하지 않습니다.
SET LONG 1000000
VARIABLE final_prompt CLOB
VARIABLE request_attributes CLOB
VARIABLE generated_sql CLOB

-- :final_prompt에 질문·선택 RDF/JSON·허용 컬럼과 SQL 지침을 바인드하세요.
-- :request_attributes에 선택 경로의 object_list 및 호출 옵션 JSON을 바인드하세요.
-- 옵션 예: {"conversation":false,"comments":false,"annotations":false,
--   "enforce_object_list":true,"object_list":[
--     {"owner":"<SCHEMA>","name":"<SOURCE_TABLE>"},
--     {"owner":"<SCHEMA>","name":"<LINK_TABLE>"},
--     {"owner":"<SCHEMA>","name":"<TARGET_TABLE>"}]}
BEGIN
  IF :final_prompt IS NULL OR :request_attributes IS NULL THEN
    RAISE_APPLICATION_ERROR(-20052, 'Bind the prepared prompt and attributes first');
  END IF;
  IF DBMS_CLOUD_AI.GET_CONVERSATION_ID IS NOT NULL THEN
    RAISE_APPLICATION_ERROR(-20051, 'Active conversation is not allowed');
  END IF;
  :generated_sql := DBMS_CLOUD_AI.GENERATE(
    prompt => :final_prompt,
    profile_name => '<PROFILE_NAME>',
    action => 'showsql',
    attributes => :request_attributes);
END;
/
PRINT generated_sql`,'repository/OntologyQueryRepository',{risk:'ai',doc:aiDoc,
      note:pair('온톨로지 질의의 실제 GENERATE 호출 형태입니다. 원 질문만 전달하면 선택한 관계가 자동 첨부되지 않습니다. 앱은 OntologyInquiry.payload/sqlPrompt에서 선택 근거와 지침을 조립하고, OntologyQueryService에서 호출 옵션을 만듭니다. 전송 미리보기 JSON만으로 최종 프롬프트 전체를 대신하지 마세요. 직접 실행하려면 이 구조에 맞는 두 CLOB을 SQLcl/JDBC에서 준비해야 합니다. 호출 옵션은 해당 요청에 적용되며 프로필을 수정하는 SET_ATTRIBUTE가 아닙니다. 생성된 SQL은 출력만 하며 실행하지 않습니다. AI 설명은 별도 설명 지침과 action=chat을 사용합니다.','This mirrors the ontology-query GENERATE call. A raw question does not automatically include selected relationships. OntologyInquiry.payload/sqlPrompt builds evidence and instructions; OntologyQueryService builds request attributes. Preview JSON alone is not the complete final prompt. Prepare both CLOBs in SQLcl/JDBC using that structure. These are per-request attributes, not SET_ATTRIBUTE profile changes. The generated SQL is only printed, not executed. AI explanations use separate explanation instructions and action=chat.')}),
    recipe('archive','저장된 온톨로지 질의 결과 조회','Read saved ontology-query results',`SELECT RECORD_ID, STATE, ACTOR, RECORDED_AT, UPDATED_AT, PAYLOAD
FROM "<SCHEMA>"."DBC_APP_RECORD"
WHERE RECORD_TYPE = 'ONTOLOGY_QUERY' AND RECORD_ID = '<RECORD_UUID>';`,'repository/AppRecordRepository')
  ],
  assistant:[
    recipe('profiles','AI 도우미의 선택 가능 프로필 조회','Read available assistant profiles',`SELECT p.PROFILE_NAME, a.ATTRIBUTE_VALUE PROVIDER, m.ATTRIBUTE_VALUE MODEL
FROM USER_CLOUD_AI_PROFILES p
JOIN USER_CLOUD_AI_PROFILE_ATTRIBUTES a
  ON a.PROFILE_NAME = p.PROFILE_NAME AND a.ATTRIBUTE_NAME = 'provider'
LEFT JOIN USER_CLOUD_AI_PROFILE_ATTRIBUTES m
  ON m.PROFILE_NAME = p.PROFILE_NAME AND m.ATTRIBUTE_NAME = 'model'
WHERE p.STATUS = 'ENABLED' ORDER BY p.PROFILE_NAME;`,'repository/AiAssistantRepository',{
      note:pair('화면의 프로필 선택은 앱 세션 상태입니다. DB 프로필을 새로 만들거나 변경하지 않습니다.','The screen selection is application session state, not profile creation/modification.')}),generate('chat','선택 프로필로 설명 요청','Request an explanation with a profile','chat')
  ],
  'problem-questions':[
    recipe('list','등록 질문 조회','Read registered problem questions',problemList,'repository/AppRecordRepository'),
    recipe('attempts','질문별 시간순 답변·스냅샷 조회','Read chronological attempts and snapshots',problemAttempts,'repository/AppRecordRepository',{
      note:pair('저장된 두 PAYLOAD의 A/B 비교·내보내기는 앱에서 수행합니다. 저장은 별도 DB 쓰기이며 추가 AI 호출은 하지 않습니다.','The app compares/exports selected stored PAYLOADs. Saving is a separate DB write with no additional AI call.')}),
    recipe('save','선택 저장의 DB 기록 단계','DB record step of explicit save',`-- Bind the complete, validated parent/attempt payload as CLOB.
INSERT INTO "<SCHEMA>"."DBC_APP_RECORD" (RECORD_ID, RECORD_TYPE, STATE, PAYLOAD)
VALUES ('<RECORD_UUID>', '<SELECT_AI_PROBLEM_OR_SELECT_AI_ATTEMPT>', 'REQUESTED', :payload_json);
UPDATE "<SCHEMA>"."DBC_APP_RECORD"
SET STATE = 'SUCCEEDED', PAYLOAD = :payload_json, UPDATED_AT = SYSTIMESTAMP
WHERE RECORD_ID = '<RECORD_UUID>' AND STATE = 'REQUESTED';`,'repository/AppRecordRepository',{risk:'write',verify:problemList,
      note:pair('저장 단계만 발췌했습니다. Parent/Attempt JSON 구조, 부모 잠금·버전·관측 토큰 검증은 앱에서 별도 수행합니다. 임의 JSON이나 기존 UUID로 실행하면 안 됩니다. 자동 COMMIT 예제는 제공하지 않습니다.','Storage steps only. Parent/Attempt JSON structure, parent locks, versions and observation-token validation are separate app checks. Do not use arbitrary JSON or an existing UUID. No automatic COMMIT is supplied.')})
  ],
  'ai-batch':[generate('showsql','등록 질문별 SQL 순차 생성','Generate SQL for each registered question','showsql'),
    recipe('attempts','선택 저장된 일괄 실행 결과 조회','Read explicitly saved batch attempts',problemAttempts,'repository/AppRecordRepository',{
      note:pair('앱은 질문마다 GENERATE를 순차 호출합니다. 생성 SQL을 자동 실행하지 않고 사용자가 선택한 결과만 저장합니다.','The app calls GENERATE sequentially per question, does not execute generated SQL and saves only explicitly selected results.')})],
  'ai-comparison':[
    generate('profiles','프로필 A/B에서 각각 SQL 생성','Generate SQL separately for profiles A and B','showsql'),
    generate('review','선택한 두 결과를 AI로 비교','Ask AI to compare two selected results','chat')
  ]
};
