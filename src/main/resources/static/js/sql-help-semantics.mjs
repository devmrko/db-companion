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
