// Source-backed examples for implementing the same operations outside this UI.
const pair=(ko,en)=>({ko,en});
const guide='/help/sql-reference.html#semantic-context';
const entry=(id,ko,en,sql,source,note,english,risk='read',verify='')=>({id,title:pair(ko,en),purpose:pair(ko,en),sql,source,
  note:pair(note,english),risk,verify,doc:guide,docKind:'project'});
const sources=`SELECT SOURCE_NAME, SOURCE_TYPE, STATUS, DESCRIPTION
FROM "<SEM_PACKAGE_OWNER>".USER_AI_SEMANTIC_SOURCES
WHERE SOURCE_NAME = '<SOURCE_NAME>';
SELECT SOURCE_NAME, ATTRIBUTE_NAME, ATTRIBUTE_VALUE
FROM "<SEM_PACKAGE_OWNER>".USER_AI_SEMANTIC_SOURCE_ATTRIBUTES
WHERE SOURCE_NAME = '<SOURCE_NAME>' ORDER BY ATTRIBUTE_NAME;`;
const native='src/main/java/com/dbcompanion/repository/SemanticContextRepository.java';
const nativeNote='DB 버전·API 서명과 실제 패키지 소유자를 먼저 확인하세요. <SEM_PACKAGE_OWNER>는 ALL_PROCEDURES의 DBMS_AI_SEM 소유자입니다. 로그인 소유자의 Source를 대상으로 실행하며 해당 패키지 EXECUTE 권한이 필요합니다. 직접 호출은 앱의 미리보기·동시 변경 검사·변경 이력을 실행하지 않습니다. DDL/API 실패 후 상태를 재조회하고 전체를 자동 재실행하지 마세요.';
const nativeEnglish='Check the installed API signature and DBMS_AI_SEM owner in ALL_PROCEDURES first. Requires package EXECUTE for sources owned by the login user. Direct calls do not perform application preview, concurrency validation or history capture. Re-read state after errors; do not replay the entire script.';
export const semanticSourceHelp=[
  entry('sources','Semantic Source·속성 조회','Read semantic sources and attributes',sources,native,nativeNote,nativeEnglish),
  entry('create','CUSTOM 전역 Source 생성','Create a global CUSTOM source',`-- Inspect the package owner and API before replacing placeholders.
SELECT OWNER, PROCEDURE_NAME FROM ALL_PROCEDURES
WHERE OBJECT_NAME = 'DBMS_AI_SEM'
  AND PROCEDURE_NAME IN ('CREATE_SEMANTIC_SOURCE','UPDATE_SEMANTIC_SOURCE');
DECLARE attrs CLOB := '{"scope":"global","global_callback_func":"<SCHEMA>.DBC_GLOSSARY_CONTEXT"}';
BEGIN
  "<SEM_PACKAGE_OWNER>".DBMS_AI_SEM.CREATE_SEMANTIC_SOURCE(
    source_name => '<SOURCE_NAME>', type => 'custom', attributes => attrs,
    status => 'enabled', description => 'Business glossary context');
END;
/`,native,nativeNote,nativeEnglish,'write',sources),
  entry('update','Source 속성 수정','Update source attributes',`-- Read and retain ALL existing attributes first. This is an example payload.
DECLARE attrs CLOB := '{"scope":"global","global_callback_func":"<SCHEMA>.DBC_GLOSSARY_CONTEXT","params":{}}';
BEGIN
  "<SEM_PACKAGE_OWNER>".DBMS_AI_SEM.UPDATE_SEMANTIC_SOURCE(
    source_name => '<SOURCE_NAME>', attributes => attrs);
END;
/`,native,nativeNote+' 현재 속성을 먼저 보관하고 유지할 속성을 포함한 JSON을 준비하세요. 앱 편집기는 CUSTOM global의 callback·params 변경만 지원합니다.',nativeEnglish+' Preserve current attributes and supply a reviewed payload. The app editor supports CUSTOM global callback/params changes.','write',sources),
  entry('enable','Source 활성화','Enable a source',`BEGIN
  "<SEM_PACKAGE_OWNER>".DBMS_AI_SEM.ENABLE_SEMANTIC_SOURCE(source_name => '<SOURCE_NAME>');
END;
/`,native,nativeNote,nativeEnglish,'write',sources),
  entry('disable','Source 비활성화','Disable a source',`BEGIN
  "<SEM_PACKAGE_OWNER>".DBMS_AI_SEM.DISABLE_SEMANTIC_SOURCE(source_name => '<SOURCE_NAME>');
END;
/`,native,nativeNote+' 이 Source를 참조하는 질의에 영향을 줄 수 있습니다.',nativeEnglish+' May affect queries referencing this source.','write',sources),
  entry('profile-link','프로필에 Semantic Source 연결','Attach a source to a profile',`BEGIN
  DBMS_CLOUD_AI.SET_ATTRIBUTE(profile_name => '<PROFILE_NAME>',
    attribute_name => 'semantic_source', attribute_value => '["<SOURCE_NAME>"]');
END;
/
SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES
WHERE PROFILE_NAME = '<PROFILE_NAME>' AND ATTRIBUTE_NAME = 'semantic_source';`,native,
    '활성화된 소스를 해당 프로필 소유자로 연결하세요. 기존 배열을 대체하므로 유지할 소스를 모두 포함해야 합니다. 직접 호출은 DB Companion의 프로필 변경 이력을 남기지 않습니다.',
    'Run as the profile owner with enabled sources. The array replaces the previous value; retain all required sources. Direct calls do not capture application profile history.','write')
];
export const glossarySearchHelp=[
  entry('tokens','한국어 형태소 토큰 추출','Extract Korean morphological tokens',`SET SERVEROUTPUT ON
DECLARE
  tokens CTX_DOC.TOKEN_TAB;
  n BINARY_INTEGER;
BEGIN
  CTX_DOC.POLICY_TOKENS(policy_name => 'DBC_BT_KO_POLICY',
    document => '<QUESTION>', restab => tokens, format => 'TEXT');
  n := tokens.FIRST;
  WHILE n IS NOT NULL LOOP
    DBMS_OUTPUT.PUT_LINE(tokens(n).token);
    n := tokens.NEXT(n);
  END LOOP;
END;
/`,'src/main/java/com/dbcompanion/repository/BusinessGlossaryRepository.java',
    '업무 용어 사전의 Oracle Text 설정을 먼저 준비하세요. KOREAN_MORPH_LEXER 정책으로 분석하며 이 호출은 용어 검색·AI 호출·업무 SQL 실행을 하지 않습니다. SET SERVEROUTPUT ON은 SQLcl 명령입니다.',
    'Prepare the glossary Oracle Text configuration first. Uses its KOREAN_MORPH_LEXER policy; this only extracts tokens, without searching terms, calling AI or executing business SQL.'),
  entry('candidates','Oracle Text 검색 후보 조회','Read Oracle Text candidates',`-- Bind an escaped query from the observed tokens, for example: {AU}
SELECT TERM_ID, TERM_TEXT, ALIASES_JSON
FROM DBC_BUSINESS_TERM
WHERE ENABLED_YN = 'Y' AND CONTAINS(SEARCH_TEXT, :text_query, 1) > 0;`,
    'src/main/resources/db/callable/glossary-api-body.sql',
    '후보 조회 단계입니다. 앱은 토큰을 이스케이프한 OR 검색식으로 후보를 찾은 뒤 POLICY_HIGHLIGHT로 질문의 정식 용어·별칭을 대조하고 겹친 표현을 정리합니다. CONTAINS 후보 전체가 최종 적용 용어는 아닙니다.',
    'Candidate stage only. The app escapes tokens into an OR query, then uses POLICY_HIGHLIGHT to match registered terms/aliases in the question and resolves overlaps. Candidates are not final context.'),
  entry('search','설치된 용어 검색 API 실행','Run the installed glossary search API',`SET LONG 1000000
SELECT DBC_GLOSSARY_API.SEARCH(TO_CLOB('<QUESTION>')) AS EVIDENCE_JSON FROM DUAL;`,
    'src/main/resources/db/callable/glossary-api-body.sql',
    '업무 용어 검색 함수 화면에서 패키지 설치·상태를 먼저 확인하세요. 토큰·검색식·일치 표현·업무 정의·SQL 기준을 JSON으로 반환합니다. 읽기 전용 검색이며 AI나 업무 SQL을 실행하지 않습니다.',
    'Install and verify the glossary package on the function page first. Returns tokens, query, matched expressions, definitions and SQL criteria as JSON; no AI or business SQL execution.'),
  entry('callback','CUSTOM 전역 콜백 시험','Test the global CUSTOM callback',`SET LONG 1000000
SET SERVEROUTPUT ON
DECLARE
  ctx JSON_OBJECT_T := JSON_OBJECT_T();
  result_json CLOB;
BEGIN
  ctx.put('user_prompt', '<QUESTION>');
  result_json := DBC_GLOSSARY_CONTEXT(ctx.to_clob());
  DBMS_OUTPUT.PUT_LINE(DBMS_LOB.SUBSTR(result_json, 8000, 1));
END;
/`,'src/main/resources/db/callable/glossary-context.sql',
    'SQLcl에서 SET SERVEROUTPUT ON을 먼저 실행하세요. JSON_OBJECT_T로 질문의 따옴표·줄바꿈을 이스케이프합니다. 전역 콜백의 term/definition 배열 시험이며 검색 JSON 전체나 applies_to를 반환하지 않습니다. 생성 SQL·업무 결과 검증은 별도입니다. 출력은 앞 8,000자입니다.',
    'Run SET SERVEROUTPUT ON in SQLcl first. JSON_OBJECT_T escapes the question. Tests the global term/definition array, not an applies_to response or the full search JSON. Validate generated SQL/results separately. Output is limited to 8,000 characters.')
];
export const semanticHistoryHelp=[entry('history','Source 설정·콜백 배포 이력 조회','Read source/configuration and callback deployment history',`SELECT RECORD_ID, ACTOR, RECORDED_AT, STATE,
       JSON_VALUE(PAYLOAD, '$.kind') AS KIND,
       JSON_VALUE(PAYLOAD, '$.name') AS OBJECT_NAME,
       JSON_VALUE(PAYLOAD, '$.operation') AS OPERATION,
       PAYLOAD
FROM DBC_APP_RECORD
WHERE RECORD_TYPE = 'SEMANTIC_CHANGE'
  AND JSON_VALUE(PAYLOAD, '$.kind') = '<SOURCE_OR_CALLBACK>'
ORDER BY SEQ DESC FETCH FIRST 30 ROWS ONLY;`,
  'src/main/java/com/dbcompanion/repository/SemanticChangeRepository.java',
  '로그인 소유자의 설치된 앱 저장소를 조회합니다. SOURCE는 설정 전후, CALLBACK은 설치 버전·실제 소스 해시·상태입니다. 코드 원문은 Git에서 관리합니다. 직접 native API 호출이나 설치 전 변경은 이 SQL로 이력이 생성되지 않습니다.',
  'Reads the installed app store of the login owner. SOURCE stores before/after configuration; CALLBACK stores deployment versions, actual source hashes and status. Code stays in Git. This query does not create history for direct calls or earlier changes.')];

glossarySearchHelp.push(entry('callback-reference','첨부 문맥에서 원 질문만 검색하는지 확인','Verify lookup uses only the original question',`SET SERVEROUTPUT ON
DECLARE
  question CLOB := '<QUESTION>';
  reference_obj JSON_OBJECT_T := JSON_OBJECT_T();
  context_obj JSON_OBJECT_T := JSON_OBJECT_T();
  reference_words CLOB := 'reference';
  expected CLOB;
  actual CLOB;
BEGIN
  context_obj.put('user_prompt',question);
  expected := DBC_GLOSSARY_CONTEXT(context_obj.to_clob());
  FOR i IN 1..100 LOOP reference_words := reference_words||' referenceword'||i; END LOOP;
  reference_obj.put('originalQuestion',question);
  reference_obj.put('definitions',reference_words);
  context_obj.put('user_prompt',question||CHR(10)||'REFERENCE DATA: business reference data'||CHR(10)||
    'BEGIN REFERENCE JSON'||CHR(10)||reference_obj.to_clob()||CHR(10)||'END REFERENCE JSON');
  actual := DBC_GLOSSARY_CONTEXT(context_obj.to_clob());
  IF DBMS_LOB.COMPARE(expected,actual)<>0 THEN
    RAISE_APPLICATION_ERROR(-20099,'Original-question lookup mismatch');
  END IF;
  DBMS_OUTPUT.PUT_LINE('PASS: original question and reference prompt match');
END;
/`,'src/main/resources/db/callable/glossary-context.sql',
 '소유자로 실행. 사전·Oracle Text 준비와 콜백 v6 이상이 필요합니다. 같은 질문의 직접 호출과 REFERENCE JSON 첨부 호출 결과를 비교하며 AI를 호출하지 않습니다. 짧은 예제 한 건의 일치만으로 모든 중첩·질문의 정확성을 보장하지 않습니다. ORA-20061은 검색 입력에 첨부 근거가 섞이는지 확인하고, 함수 화면의 미리보기로 인식된 이전 콜백만 업데이트하세요. 모델 출력 토큰을 늘리는 조치와 구분합니다.',
 'Run as the owner after dictionary/Oracle Text setup and callback v6 or later. Compares direct and reference-enriched callback results without AI. This short example does not prove all nested inputs or questions. For ORA-20061 inspect whether attached evidence entered lookup; use the function preview to update only recognized earlier callbacks. Increasing model output tokens is a separate action.'));

export const ddsIdentityHelp={...entry('identity','로그인·DB 실행·DDS 신원 대조','Compare login, database and DDS identities',`SELECT SYS_CONTEXT('APEX$SESSION','APP_USER') AS APP_USER,
       SYS_CONTEXT('USERENV','SESSION_USER') AS SESSION_USER,
       SYS_CONTEXT('USERENV','CURRENT_USER') AS CURRENT_USER,
       JSON_VALUE(ORA_END_USER_CONTEXT.username,'$' RETURNING VARCHAR2(128)) AS DDS_USER
FROM SYS.DUAL;
SELECT <ROW_ID_COLUMN>, <POLICY_COLUMN>
FROM "<SCHEMA>"."<TABLE_NAME>" ORDER BY <ROW_ID_COLUMN>;`,
 'src/main/java/com/dbcompanion/service/DdsRuntimeProbeService.java',
 '실제 APEX·MCP·SQLcl의 최종 사용자 연결에서 같은 SQL을 A→B→A로 실행하세요. APP_USER는 APEX 밖에서 NULL일 수 있고, CURRENT_USER=XS$NULL을 사전 소유 스키마로 해석하지 않습니다. 지역 조건을 SQL에 추가하지 않고 실제 신원·허용 행·제외 행을 정책 기대값과 대조합니다. 로그인 성공이나 소유자 조회만으로 DDS 적용을 판정하지 않습니다. 이 SQL은 신원을 설정하거나 권한을 부여하지 않습니다.',
 'Run on the actual end-user APEX, MCP or SQLcl connection, A→B→A. APP_USER can be null outside APEX; CURRENT_USER=XS$NULL is not the dictionary owner. Do not add the policy filter to SQL: compare actual identity, allowed and excluded row IDs against policy expectations. Login or owner-query success alone does not prove DDS enforcement. This SQL neither attaches identity nor grants privileges.'),doc:'/help/sql-reference.html#runtime-identity'};

export const callableAiHelp=['CONTEXT','SQL','QUERY'].map(mode=>({...entry(mode.toLowerCase(),
 {CONTEXT:'1. 근거·첨부 프롬프트 확인',SQL:'2. 네이티브 SQL 생성',QUERY:'3. 생성 SQL로 실제 조회'}[mode],
 {CONTEXT:'1. Inspect evidence and prepared prompt',SQL:'2. Generate native SQL',QUERY:'3. Generate SQL and query rows'}[mode],
 `SET LONG 1000000
SELECT "<SCHEMA>".DBC_AI_QUERY.ASK(
  p_question => '<QUESTION>', p_profile => '<PROFILE_NAME>',
  p_use_glossary => 1, p_use_ontology => 0,
  p_tables => '[]', p_mode => '${mode}', p_max_rows => 200
) AS RESPONSE_JSON FROM SYS.DUAL;`,
 'src/main/resources/db/callable/body.sql',
 'SCHEMA는 패키지 소유자, PROFILE_NAME은 실제 호출 경로에서 사용 가능한 프로필로 치환하세요. 질문의 작은따옴표는 두 번 쓰세요. CONTEXT는 사전·첨부 프롬프트만 확인하며 AI 호출 없음. SQL은 AI1회·실행 없음. QUERY는 별도로 AI1회 생성 후 조회하며 앞 단계 SQL을 재사용하지 않습니다. 정확히 같은 SQL을 권한별로 비교하려면 SQL 모드 결과를 검토한 뒤 최종 사용자 연결에서 별도 SELECT로 실행하세요. 프로필·Source 공유, 콜백 EXECUTE, 사전 읽기, 실제 업무 데이터 권한은 각각 확인. 소유자 생성 성공이 DDS 직접 생성 성공을 뜻하지 않습니다.',
 'SCHEMA is the package owner; choose a profile available on the actual caller path. Escape question quotes by doubling them. CONTEXT reads evidence/prompt without AI; SQL generates once without execution. QUERY makes a separate generation request then queries; it does not reuse the preceding SQL. To compare exactly the same SQL across users, review SQL-mode output and run that SELECT separately on each end-user connection. Check profile/source sharing, callback EXECUTE, dictionary reads and business data privileges independently. Owner generation does not prove DDS direct generation.',mode==='CONTEXT'?'read':'ai'),doc:'/help/sql-reference.html#callable-pipeline'}));
