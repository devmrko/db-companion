package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;
import java.time.LocalDate;
import java.util.Set;

/** Text-only handoff: no database or provider calls. */
public final class DemoDataPrompt {
    private DemoDataPrompt() {}
    public record Input(String scenario, String questions, String rules, String date, int rows) {}
    public static String build(String owner, String selected, Input input) {
        if (owner == null || !owner.matches("[A-Z][A-Z0-9_$#]{2,127}")
                || Set.of("ADMIN", "SYS", "SYSTEM", "PUBLIC").contains(owner) || !owner.equals(selected))
            throw new IllegalArgumentException(UiMessages.text("demoData.ownerRequired", "전용 실습 계정으로 로그인하고 같은 스키마를 선택하세요."));
        if (input == null || input.rows() < 1 || input.rows() > 10000
                || !valid(input.scenario(), 2000) || !valid(input.questions(), 4000) || !valid(input.rules(), 4000))
            throw new IllegalArgumentException(UiMessages.text("demoData.invalid", "필수 입력과 행 수(1~10,000)를 확인하세요."));
        try { LocalDate.parse(input.date()); }
        catch (java.time.DateTimeException | NullPointerException ex) {
            throw new IllegalArgumentException(UiMessages.text("demoData.invalid", "필수 입력과 행 수(1~10,000)를 확인하세요."));
        }
        return UiMessages.text("demoData.prompt", """
                Oracle 실습용 테이블과 합성 데이터를 만들겠습니다.
                대상: 새 전용 스키마 {0}, Oracle Autonomous AI Database 26ai.
                업무 상황: {1}
                대표 질문:
                {2}
                업무 규칙과 예외:
                {3}
                기준일: {4}, 합성 사실 데이터: {5}행.

                먼저 SQLcl의 실제 연결 DB·SESSION_USER·CURRENT_SCHEMA가 지정 대상과 일치하는지 확인하세요.
                Wallet 내용·비밀번호는 출력하거나 파일·프롬프트에 넣지 마세요.
                기존 객체 목록을 확인하고 같은 이름이 있으면 중단하세요. 기존 객체의 수정·삭제는 하지 마세요.
                아래 파일을 작성하고 SQL을 검토한 뒤 지정한 빈 실습 스키마에서 순서대로 실행하세요.
                01-schema.sql: 질문을 재현할 테이블 1~3개, 키·필요 관계, 컬럼 타입. 업무 정답을 담은 COMMENT·Annotation은 아직 설정하지 마세요.
                02-data.sql: 실제 고객 자료를 사용하지 않는 합성 데이터, 정상·중복·제외·기간 경계·사용자 A/B 접근 범위가 다른 행. 재현 가능한 값을 사용하세요.
                03-verify.sql: 객체·행 수·키 무결성·대표 질문의 기준 SQL·기대 결과. 생성 SQL과 별도로 기대값도 계산해 대조하세요.
                04-business-rules.md: 업무 용어·별칭·집계/필터 기준과 A/B별 허용·제외 행·기대 결과. 교육용 가정임을 표시하세요.
                DDL은 별도로 커밋됩니다. 중간 실패 시 현재 상태를 확인하고 전체 파일을 자동 재실행하지 마세요.
                실행 후 실제 객체와 건수·기준값을 조회하고 성공/실패를 보고하세요.
                데이터 준비가 끝나면 DB Companion에서 대상 객체를 새로고침하세요.
                그다음 메타데이터·용어·Select AI 프로필·CUSTOM Semantic Context·이력·DDS를 순서대로 구성합니다.
                """, owner, input.scenario().strip(), input.questions().strip(), input.rules().strip(), input.date(), input.rows());
    }
    private static boolean valid(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.indexOf('\0') < 0;
    }
}
