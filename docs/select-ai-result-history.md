# 실행 이력에서 당시 조회 결과 보기

DB Companion에서 SQL 실행 뒤 **결과 보관**을 선택하면 질문·SQL·실행일시·당시 표시한 결과 행을 DB에 저장한다. **실행 이력 → 보관한 조회 결과**에서 재로그인이나 앱 재시작 뒤에도 다시 연다. 이력을 여는 동작은 AI 호출이나 SQL 재실행을 하지 않는다.

## 사용 순서

1. 일반 업무 계정으로 로그인하고 해당 계정의 스키마를 선택한다. ADMIN도 다른 사용자 기록을 이 화면으로 열 수 없다.
2. **자연어 데이터 조회** 또는 **설정 도우미 → 질문으로 확인**에서 질문을 입력한다. AI 전송 내용을 확인해 SQL을 생성하고, 생성 SQL을 따로 확인한 뒤 실행한다.
3. 결과 표 아래에서 보관 범위를 읽고 **결과 보관**을 선택한다. 조회 행을 로그인 계정 DB의 기존 `DBC_APP_RECORD`에 기록하며 모델에 다시 보내지 않는다.
4. **보관한 조회 결과** 링크를 연다. 기간은 **보관일의 한국 날짜** 기준이고 목록에는 원래 실행일시를 표시한다. 프로필·질문으로 검색할 수 있다.
5. 항목을 선택해 질문·SQL·실행 신원·실행일시·결과 표를 확인한다. 현재 데이터가 달라져도 저장된 결과는 바뀌지 않는다.

공통 저장소가 없다면 **문제 질문** 화면에서 상태·준비 안내를 확인한다. 이 기능이 자동으로 테이블을 만들거나 권한을 추가하지 않는다. 조회가 성공해도 저장소·용량 문제로 보관은 실패할 수 있다. 실패 메시지를 확인하고 현재 결과가 남아 있는 동안 다시 보관한다. SQL은 자동으로 다시 실행하지 않는다. 같은 실행의 보관을 재요청해도 같은 기록 ID를 사용한다.

## 무엇이 남는가

| 원천 | 저장 정보 | 당시 조회 행 |
|---|---|---|
| 저장 대화의 `SHOWSQL` | 질문과 생성 SQL | 포함하지 않음 |
| 저장 대화의 `RUNSQL` | 실제 교육용 ADB 시험에서 질문과 생성 SQL. 호출 반환값은 조회 행 JSON | 이력에는 포함하지 않았음 |
| 저장 대화의 `NARRATE` | 숫자가 포함될 수 있는 AI 설명문 | 원본 결과 표와 구분 |
| DB Companion 결과 보관 | 질문, 프로필명, 실행 SQL·해시, 신원·일시, 표시한 열·행·NULL·생략 표시 | 명시적으로 보관한 범위 포함 |

Oracle 동작은 **2026-11-27 교육용 ADB에서 액션별 실제 호출 후 새 DB 연결로 이력을 읽은 범위**다. 모든 DB 버전·패치의 동일 동작을 보장하지 않는다. 관련 원천은 [DBMS_CLOUD_AI 뷰](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/dbms-cloud-ai-views.html), [패키지](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/dbms-cloud-ai-package.html), [저장 대화](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/select-ai-enable-conversations.html)를 참조한다.

## 보관 범위와 한계

- 성공한 SQL 조회만 보관한다. 0행도 성공 결과다. 생성만 한 SQL, 실패한 조회, 이전 실행과 맞지 않는 결과는 보관하지 않는다.
- 표시와 같은 최대 200행·셀별 2,000자 한도다. 잘린 행·셀 표시는 유지한다. 전체 결과 내보내기 기능이 아니다. JSON 보관 한도는 1,000,000자이며 초과하면 거절한다.
- **보관 전의 결과는 소급 복원하지 못한다.** 저장 대화·SQL 매핑·공유 커서 통계가 원본 조회 행을 대신하지 않는다. 재실행한 현재 값을 과거 값으로 표시하지 않는다.
- 기존 결과 표가 세션에 남아 있는 동안 보관해야 한다. 다른 질문 실행이나 로그아웃 뒤에는 미보관 결과를 가져오지 못한다.
- 보관은 선택 동작이며 자동 수집하지 않는다. 동일 DB 계정으로 로그인한 사용자는 해당 스키마의 보관 자료를 공유한다. DB 계정을 여러 사람에게 공유하지 않는 것을 전제로 한다.
- 기록은 삭제 전까지 유지한다. 현재 삭제 UI는 제공하지 않는다. 소유자는 아래 SQL의 ID를 치환해 해당 유형 한 건만 삭제할 수 있다. DB 쓰기이므로 대상과 조직의 보존 기준을 먼저 확인한다.

```sql
-- 자신의 스키마에서 실행. <SAVED_RESULT_UUID>는 상세 링크의 id로 치환.
SELECT RECORD_ID, RECORD_TYPE, RECORDED_AT FROM DBC_APP_RECORD
WHERE RECORD_TYPE = 'SELECT_AI_RESULT' AND RECORD_ID = '<SAVED_RESULT_UUID>';
DELETE FROM DBC_APP_RECORD
WHERE RECORD_TYPE = 'SELECT_AI_RESULT' AND RECORD_ID = '<SAVED_RESULT_UUID>';
COMMIT;
```

보관 자료에는 실제 업무 조회 행이 포함된다. DB 백업·접근 제어·보존 정책에 이 테이블을 포함해야 한다. 비밀번호·Wallet·Credential 본문은 이 기록에 담지 않는다.

## 검증 범위

교육용 합성 데이터로 일반 집계, 0행, NULL·HTML 모양 문자열의 실제 생성·SQL 실행·보관·재조회 값을 비교했다. 앱 재시작·새 로그인 뒤에도 일치했다. 중복 보관, 미동의, 오래된 결과 ID, 잘못된 ID, 없는 ID, 날짜 범위와 검색 필터를 확인했다. 추가 실제 모델 질문에서는 200행·2,000자 셀 생략 표시를 확인하고 Chrome의 결과 보관 버튼으로 저장했다. Chrome에서 실제 보관 자료 4건의 표와 390px 배치를 확인했고 이력 조회 중 POST·AI·SQL 실행 요청이 없었다. 다른 업무 데이터·모델·APEX/MCP 외부 실행의 자동 수집은 이 검증에 포함하지 않는다.

API: `POST /ai-test/results/save`는 `{resultId, consent:true}`만 받는다. 클라이언트가 결과 행이나 SQL을 주입할 수 없고 서버 세션의 현재 실행 결과를 사용한다. `GET /ai-executions/results/list`와 `/detail?id=…`는 해당 계정의 저장 자료만 읽는다.
