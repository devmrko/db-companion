package com.dbcompanion.common.exception;

/** Separates English diagnostic messages from user-facing Korean messages. */
public class AppException extends IllegalArgumentException {
    private final Code code;

    public AppException(Code code) {
        super(code.message);
        this.code = code;
    }

    public String userMessage() {
        return code.userKey == null ? com.dbcompanion.common.i18n.UiMessages.literal(code.userMessage)
                : com.dbcompanion.common.i18n.UiMessages.text(code.userKey, code.userMessage);
    }

    public enum Code {
        SQL_MAPPING_AMBIGUOUS("Multiple SQL mappings have the same key.", "같은 식별정보의 SQL 매핑이 여러 건입니다. 목록을 새로고침해 주세요."),
        EXECUTION_SCHEMA_CHANGED("The execution history schema has changed.", "스키마가 변경되었습니다. 실행 이력 목록을 다시 조회해 주세요."),
        EXECUTION_OWN_SCHEMA_ONLY("Execution history is visible only to its owner.", "이 실행 이력은 로그인 계정의 기록만 조회할 수 있습니다. 본인 스키마를 선택해 주세요."),
        AGENT_SCHEMA_CHANGED("The selected schema has changed.", "스키마가 변경되었습니다. Team 목록에서 다시 선택해 주세요."),
        AGENT_OBJECT_NOT_ACCESSIBLE("The agent object does not exist or is not accessible.", "항목이 없거나 조회 권한이 없습니다."),
        AGENT_TASK_NOT_IN_TEAM("The task is not assigned to the selected team.", "선택한 Team에 연결된 Task가 아닙니다."),
        AGENT_RELATION_INVALID("The agent relationship attributes are invalid.", "agents 또는 tools 관계 설정의 형식을 확인해 주세요."),
        PROFILE_SCHEMA_RESTRICTED("Only ADMIN can read profiles in other schemas.", "다른 스키마의 프로필은 ADMIN 계정에서만 조회할 수 있습니다. 본인 스키마를 선택해 주세요."),
        PROFILE_NOT_ACCESSIBLE("The profile does not exist or is not accessible.", "프로필이 없거나 조회 권한이 없습니다."),
        TABLE_NOT_ACCESSIBLE("The table does not exist or is not accessible.", "테이블이 없거나 조회 권한이 없습니다."),
        CREDENTIALS_REQUIRED("Database username and password are required.", "DB 사용자명과 비밀번호를 입력해 주세요."),
        SCHEMA_NOT_ACCESSIBLE("The selected schema is not accessible.", "접근 가능한 스키마를 선택해 주세요."),
        WALLET_NOT_CONFIGURED("Oracle wallet path is not configured.", ".env에 Wallet 경로를 설정해 주세요."),
        WALLET_LIST_INVALID("The registered wallet list is invalid.", "ORACLE_WALLET_PATHS에 절대 경로의 JSON 문자열 배열을 설정해 주세요. 최대 32개입니다.", "login.wallet.configInvalid"),
        WALLET_SELECTION_INVALID("The selected wallet is not registered.", "등록된 Wallet을 선택해 주세요. 목록이 변경됐다면 로그인 화면을 새로고침해 주세요.", "login.wallet.invalid"),
        WALLET_FILES_MISSING("Required wallet files are missing or unreadable.", "Wallet 폴더의 tnsnames.ora와 cwallet.sso를 확인해 주세요."),
        SESSION_LIMIT_REACHED("The database session limit has been reached.", "접속 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."),
        TNS_FILE_TOO_LARGE("The TNS configuration exceeds the size limit.", "tnsnames.ora 파일 크기를 확인해 주세요."),
        TNS_ALIASES_MISSING("No service aliases were found in the TNS configuration.", "tnsnames.ora에서 접속 서비스를 찾지 못했습니다."),
        TNS_FILE_UNREADABLE("The TNS configuration cannot be read.", "Wallet 폴더의 tnsnames.ora를 읽을 수 없습니다."),
        TNS_ALIAS_INVALID("The selected TNS alias is not registered in the wallet.", "Wallet에 등록된 접속 서비스를 선택해 주세요.");

        private final String message;
        private final String userMessage;
        private final String userKey;

        Code(String message, String userMessage) {
            this(message, userMessage, null);
        }

        Code(String message, String userMessage, String userKey) {
            this.message = message;
            this.userMessage = userMessage;
            this.userKey = userKey;
        }
    }
}
