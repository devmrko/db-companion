# 실습 데이터 준비: 로컬 에이전트 실행

엔지니어가 선택한 업종·기간·규모를 로컬 에이전트에 전달하여 가상 테이블과 데이터를 준비하는 기능입니다.
앱은 프롬프트와 비밀번호 없는 설정 파일만 만듭니다. AI 호출·SQLcl 실행·DB 변경은 앱에서 하지 않습니다.
실제 실행은 사용자의 Claude Code 또는 Codex와 SQLcl이 담당합니다.

[English](scenario-data.en.md) · [첫 사용 가이드](GETTING-STARTED.ko.md)

## 파일 만들기

1. 전용 실습 계정으로 로그인하고 자기 스키마를 선택한 뒤 **시작 → 실습 데이터 준비**(`/db/demo-data`)를 엽니다. 이전 `/db/scenario-data` 주소는 이 화면으로 이동합니다.
2. `.env`의 `ORACLE_WALLET_PATH` 또는 `ORACLE_WALLET_PATHS`에 등록된 Wallet과 해당
   `tnsnames.ora`의 TNS 별칭을 선택합니다. 기존 로그인과 같은 Wallet 목록을 사용합니다.
   대상 계정은 로그인한 전용 계정으로 고정됩니다. 다른 스키마를 선택하거나 SYS·SYSTEM·ADMIN·PUBLIC으로 로그인하면 생성하지 않습니다.
3. 이커머스·물류·병원·학교·공공 또는 자유 설명을 고르고 업무 상황·질문·규칙과 예외,
   합성 사실 데이터 건수(1~10,000), 시작일·종료일(1900~2100), 접두어를 입력합니다. 기간은 양끝을 포함합니다.
   접두어는 영문자로 시작하고 밑줄로 끝나는 2~16자 영문·숫자·밑줄이며 대문자로 변환합니다.
   같은 접두어의 기존 객체라도 수정·삭제하지 않습니다. 이름 충돌 시 중단하고 새 접두어나 별도 빈 실습 스키마를 준비하세요.
4. **프롬프트 만들기** 후 두 파일을 검토하여 복사하거나 다운로드합니다. **조건 수정**으로 돌아가 입력을 바꾸면 다시 생성하세요.
   `scenario-config.json`에는 선택한 Wallet 절대 경로가 포함됩니다. 비밀번호·Wallet 내용은 없습니다.
   설명에는 비밀번호, 고객 정보, 실존 인물·기업 정보를 넣지 마세요.

## 로컬 실행 준비

DB Companion 저장소 루트에 `scenario-prompt.md`, `scenario-config.json`을 저장합니다.
생성 파일과 `scenario-results/`는 Git에서 제외됩니다. 설정을 외부 공유하지 마세요.
Python 3와 SQLcl이 필요합니다. `sql -V`로 SQLcl을 확인합니다. 없으면 에이전트가 OS별
방법을 설명하고 설치 승인을 받아야 합니다. 자동 설치하지 않습니다.
[Oracle SQLcl 안내](https://docs.oracle.com/en/database/oracle/sql-developer-command-line/25.4/sqcug/startup-sqlcl-settings.html)를 참고하세요.

사용자가 직접 다음을 수행합니다. 에이전트에게 비밀 파일을 읽게 하거나 비밀번호를 대화에 넣지 마세요.

```sh
mkdir -p ~/.dbcompanion
chmod 700 ~/.dbcompanion
(umask 077; cp scripts/scenario/scenario.env.example ~/.dbcompanion/scenario.env)
chmod 600 ~/.dbcompanion/scenario.env
```

기존 파일이 있다면 위 `cp`로 덮어쓰지 말고 사용자가 편집기로 수정합니다.
`USERNAME`, `PASSWORD`, `WALLET_PATH`, `TNS_ALIAS`를 채웁니다. `KEY=value`의 값은
따옴표나 셸 확장 없이 문자 그대로 사용합니다. 비밀번호에 큰따옴표·제어 문자는 지원하지 않습니다.
계정은 대문자 비인용 식별자이며 SYS·SYSTEM·ADMIN·PUBLIC을 사용할 수 없습니다.
Wallet 경로·TNS·계정은 내려받은 JSON과 일치해야 합니다. 비밀 파일은 저장소 밖의
일반 파일이어야 하며 본인 소유·권한 600 또는 400이어야 합니다. 심볼릭 링크·하드 링크와
macOS 확장 ACL도 거부합니다.

Claude Code 또는 Codex에서 다음과 같이 요청합니다.

> scenario-prompt.md와 scenario-config.json을 읽고 시나리오 데이터를 준비해 주세요.
> 비밀 파일과 Wallet 내용은 읽지 말고, 접속은 지정 래퍼만 사용하세요.
> 설치는 먼저 확인받고, 기존 객체가 있으면 수정·삭제하지 말고 중단하세요.

에이전트는 검토한 SQL을 결과 폴더에 저장하고 다음 형태로 실행합니다.

```sh
sh scripts/scenario/sqlcl.sh scenario-config.json scenario-results/DEMO_/run.sql
```

`DEMO_`는 선택한 접두어로 바꿉니다. 래퍼는 JSON과 비밀 파일의 대상 일치를 확인하고
비밀번호를 프로세스 인자가 아닌 SQLcl 표준입력으로 전달합니다. SQLcl `-R 3`으로
HOST·SPOOL·외부 스크립트 명령을 제한합니다. 출력의 비밀번호는 가리고
`sqlcl-output.log`를 남깁니다(호출마다 덮어씀). SQL 스크립트·정답표·요약은 에이전트가 작성합니다.
SQLcl 오류 코드 외에 ORA-/SP2- 오류 출력도 실패로 처리합니다. 실패 시 남은 DDL을 확인하세요.

macOS/Linux를 지원합니다. Windows에서는 WSL 내부 파일·SQLcl·Python을 사용합니다.
네이티브 Windows/PowerShell 래퍼는 제공하지 않습니다. Python 래퍼도 Windows ACL을
검증할 수 없어 Windows에서 직접 실행하면 접속을 거부합니다.

## 관리자용 전용 계정 준비

관리자가 전용 계정을 별도로 준비하고 다음 예시를 검토합니다. 앱이나 생성 프롬프트는
계정을 만들거나 권한을 부여하지 않습니다. 계정명·테이블스페이스·quota는 환경에 맞춰 정합니다.

```sql
-- 기존 전용 계정에 관리자가 검토 후 적용하는 예시
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO SCENARIO_USER;
ALTER USER SCENARIO_USER QUOTA 100M ON USERS;
```

- `CREATE SESSION`: 로그인 허용.
- `CREATE TABLE`: 자기 스키마의 테이블 생성.
- `CREATE VIEW`, `CREATE SEQUENCE`: 필요할 때 자기 뷰·시퀀스 생성. 이번 프롬프트는 테이블만
  만들므로 이것만 실행한다면 두 권한을 생략할 수 있습니다.
- 제한된 quota: 자기 스키마 저장 공간의 상한. 규모에 따라 관리자가 검토합니다.

DBA·ANY 권한, PUBLIC 부여, UNLIMITED TABLESPACE는 사용하지 않습니다. 기존에 과도한
권한을 가진 계정을 재사용하지 마세요. 자기 테이블의 코멘트·PK/FK·데이터 생성 범위만 허용합니다.

## 결과 확인과 한계

프롬프트는 1~3개 테이블의 PK/FK, 업무 용어, DB 내부 가상 데이터 생성, 건수·참조 무결성·기간 검증,
약 10개 검증 질문과 기준 SQL·계산 결과를 요구합니다. 정답표는 기준 SQL이 계산한 참고값이며
독립적인 업무 정답 보증이 아닙니다. 실행 성공이나 AI 설명만으로 데이터 정확성을 판단하지 마세요.

`01-schema.sql`, `02-data.sql`, `03-verify.sql`, `04-business-rules.md`를 순서대로 검토합니다.
코멘트 초안은 업무 규칙 파일에 남기며 업무 정답을 담은 COMMENT·Annotation은 이후 메타데이터 실습에서 설정합니다.
정상·중복·제외·기간 경계·A/B 접근 범위가 다른 가상 행과 독립적으로 계산한 기대값을 대조합니다.
부분 실패 시 현재 상태를 확인하고 전체 스크립트를 자동 재실행하지 않습니다.

래퍼는 자격 증명 전달 도구이며 SQL 샌드박스가 아닙니다. SQL과 신규 객체 이름을 사람이
검토하고 최소 권한 계정을 사용해야 합니다. 설치된 SQLcl 및 그 전역 시작 스크립트도 신뢰할 수
있어야 합니다. 저장소의 SQLPATH와 현재 디렉터리 시작 스크립트를 상속하지 않도록 실행하지만,
SQLcl 설치 자체의 전역 설정을 격리한다고 보장하지 않습니다. DDL은 자동 커밋되므로 rollback으로
삭제·생성을 되돌릴 수 없습니다. 래퍼는 SQL 오류 시 자동 재시도하지 않습니다.

자동 테스트는 가상 Wallet/TNS, 생성 결과, 입력 제한, 번역, 권한 검사, 가짜 SQLcl의
표준입력·출력 처리만 확인합니다. 실제 SQLcl 접속·Oracle DDL/DML·업무 정답은 이 변경의 검증 범위 밖입니다.
