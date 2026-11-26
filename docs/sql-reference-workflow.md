# DB Companion으로 구성하고 SQL을 참고해 자체 구현하기

DB Companion에서 기능을 구성·검증하면서 실제 DB API와 SQL을 확인할 수 있습니다. 같은 기능을 다른 UI·SQLcl·자체 코드로 구현할 때 대응 SQL과 생성 코드를 참고합니다. 아래는 새 화면을 만드는 필수 절차가 아니라 기존 기능의 SQL 위치 안내입니다.

## SQL을 찾는 위치

1. 해당 메뉴의 **SQL?**에서 작업을 선택합니다. 목적·SQL·변수·권한·영향·결과 확인과 원본 코드 경로를 확인합니다.
2. 설치·생성 기능은 **미리보기 / 실제로 실행할 DDL**에서 선택한 소유자·객체가 반영된 전체 구문을 확인합니다.
3. 예제의 자리표시자와 바인드를 실제 환경에 맞게 지정합니다. ADMIN 작업은 ADMIN 세션에서 수행하고, Source·프로필·용어는 해당 소유자로 수행합니다.
4. 적용 전 상태를 보관하고 적용 후 실제 DB 값·대표 질문 결과를 대조합니다. SQL 예제만 직접 실행하면 앱의 권한 검사·동시 변경 검사·변경 이력이 자동으로 따라오지 않습니다.

| 작업 | DB Companion의 SQL 위치 | 확인 내용 |
| --- | --- | --- |
| 실습 테이블·가상 데이터 | 시작 → 실습 데이터 준비의 생성 프롬프트 → Codex가 작성한 SQL 파일 | 업무별 DDL·적재·독립 검증 SQL. 고정 데이터 SQL을 자동 실행하는 기능이 아님 |
| 계정·권한·네트워크 | ADMIN 조치 안내·계정 생성/권한의 실행 전 미리보기 | 선택한 사용자·권한·네트워크 범위의 실제 SQL |
| 코멘트·Annotation | 테이블 상세의 SQL?와 편집 미리보기 | COMMENT·ALTER 및 실제 조회 SQL |
| 메타데이터 이력 | 설치 확인·수집 설정의 DDL 미리보기, SQL?의 직접/위임 이력 조회 | 패키지·트리거 설치, 수집 상태, 대상별 조회 위임 |
| 용어 등록·수정 | 업무 용어 사전의 SQL / PLSQL로 관리하려면? | 조회·등록·버전 조건 수정·이력 조회 |
| 한국어 검색 설정 | 업무 용어 사전의 사전 테이블 생성·Oracle Text 설정 미리보기 | KOREAN_MORPH_LEXER·정책·CONTEXT 인덱스와 ON COMMIT 동기화 |
| 형태소·용어 검색 | 업무 용어 사전 SQL? | POLICY_TOKENS·CONTAINS 후보·검색 API·CUSTOM 콜백 시험 |
| 검색 함수·콜백 | 함수 → 업무 용어 검색 함수의 코드·실제 실행 DDL, SQL? | 패키지·wrapper·전역 콜백 전체 코드와 시험 SQL |
| 첨부 문맥 재검색 확인 | 업무 용어 사전 또는 검색 함수 SQL? → 첨부 문맥에서 원 질문만 검색하는지 확인 | 원 질문과 긴 REFERENCE JSON 첨부의 콜백 결과 비교. AI 호출 없음 |
| Semantic Source | AI 프로필 → Semantic Context의 SQL? | 조회·CREATE/UPDATE·ENABLE/DISABLE·프로필 연결 |
| Source·콜백 배포 이력 | 설정 변경 이력의 SQL?와 저장소 설치 미리보기 | 설정 전후값 또는 배포 버전·해시·결과 조회 |
| AI 프로필·변경 이력 | AI 프로필의 SQL?·속성 편집·변경 이력 | CREATE_PROFILE·SET_ATTRIBUTE·DBC_AI_HISTORY |
| SQL 생성·검토·실행 | 자연어 데이터 조회의 SQL?·생성 결과·실행 미리보기 | GENERATE의 showsql, 생성된 업무 SQL, 실행 대상 확인 |
| 사용자용 AI 함수 | DB 함수 → 사용자용 AI 함수 → SQL? | CONTEXT·SQL·QUERY 단계별 SQL, 소유자·프로필·질문 치환. QUERY는 앞 단계 SQL을 재사용하지 않음 |
| Agent·Tool·실행 이력 | AI 에이전트의 SQL?·생성 미리보기, 실행 이력 SQL? | CREATE_TOOL/AGENT/TASK/TEAM·RUN_TEAM·조회 뷰. 사용자 Tool 함수는 업무별 구현 |
| DDS 정책·조회 감사 | DDS의 SQL?·정책/감사 생성·보관 미리보기 | 사용자·역할·Data Grant, 실제 신원, 감사 정책·보관 DDL과 조회 |
| DDS 최종 사용자 대조 | DDS SQL? → 로그인·DB 실행·DDS 신원 대조 | 실제 연결의 APP_USER·SESSION_USER·CURRENT_USER·DDS_USER와 허용/제외 행 ID 비교. 로그인 성공과 DDS 적용 구분 |

APEX Ask Oracle 설치와 Codex MCP 등록은 외부 앱·클라이언트 구성입니다. DB Companion 내부 DB 설정 SQL과 구분하며 [첫 사용 안내](GETTING-STARTED.ko.md)와 기능별 공식 설치 문서를 참고합니다. MCP의 실제 조회 SQL에는 사용자 신원·허용/제외 데이터·기대 결과 검증을 포함합니다.

## 질문과 업무가 늘어날 때

새 표현은 별칭으로, 새 업무 기준은 정의·SQL 조건으로 추가합니다. 기존 검색·CUSTOM 콜백·프로필 경로를 재사용하고 검색 반환·생성 SQL·기준 결과와 기존 질문을 다시 확인합니다. 새 데이터나 접근 범위가 필요하면 테이블·대상 객체·권한도 보완합니다. 용어 추가만으로 모든 질문의 정답을 보장하지 않습니다.

Source 설정 이력의 상세 범위는 [설정 변경 이력](semantic-source-history.md)을 참고합니다.

## 새 ADB의 OCI 모델 호출 준비

Credential의 SQL? → **Resource Principal 활성화 안내**에서 DB 활성화 SQL과 OCI IAM 선행 조건을 확인합니다. DB 사용자에게 패키지 실행 권한을 주고 프로필을 생성하는 것만으로 OCI 모델 추론 권한이 완성되지는 않습니다. 관리자는 새 ADB가 정책 대상인지, 모델의 구획·리전·모델 ID가 맞는지 확인해야 합니다. 기존 ADB 하나에 제한한 정책은 새 ADB를 포함하지 않습니다.

설정 후 작은 대표 질문 한 건으로 실제 모델 호출·SQL·결과를 검증합니다. `ORA-20404`가 나면 모델 존재 여부와 접근 권한을 함께 확인하고, 실패 기록을 보존한 뒤 원인을 바꾼 경우에만 명시적으로 재검증합니다. [OCI Resource Principal 준비](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/resource-principal.html)와 [모델별 추론 권한 제한](https://docs.oracle.com/en-us/iaas/Content/generative-ai/limit-model-access.htm)을 참고합니다.
