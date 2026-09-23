#!/usr/bin/env bash
# Shared implementation: wrappers work from any current directory. Never source .env.
set -euo pipefail
umask 077
APP_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUN_DIR="$APP_ROOT/.run"
RUN_JAR="$RUN_DIR/app.jar"
PID_FILE="$RUN_DIR/app.pid"
IDENTITY_FILE="$RUN_DIR/app.identity"
PORT_FILE="$RUN_DIR/app.port"
JAVA_FILE="$RUN_DIR/java.path"
LOG_FILE="$APP_ROOT/logs/app.log"
LOCK_DIR="$RUN_DIR/lifecycle.lock"
ACTION="${1:-}"
APP_PID=""

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
    printf '%s\n' 'Usage: ./startup.sh | ./shutdown.sh | ./restart.sh' \
        'Build first: mvn package. PORT defaults to 8080; restart preserves the recorded port.' \
        'Optional: JAVA_HOME, STARTUP_TIMEOUT (45 seconds), SHUTDOWN_TIMEOUT (45 seconds).' \
        'Files: .run/app.jar, .run/app.pid, logs/app.log. DB sessions end on restart.'
}
if [[ "${2:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# == 1 && "$ACTION" =~ ^(start|stop|restart)$ ]] || { usage; exit 1; }
mkdir -p "$RUN_DIR"
mkdir "$LOCK_DIR" 2>/dev/null || fail "다른 기동/종료 작업이 진행 중입니다. 남은 잠금이면 실행 중인 스크립트가 없는지 확인한 뒤 rmdir '$LOCK_DIR'로 해제하세요."
trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cd "$APP_ROOT"
LC_ALL=C ps -p "$$" -o pid= >/dev/null 2>&1 || fail '프로세스 조회 권한이 없습니다. PID 기록을 변경하지 않았습니다.'

process_alive() {
    local state
    state="$(LC_ALL=C ps -p "$1" -o stat= 2>/dev/null)" || return 1
    [[ -n "$state" && "$state" != *Z* ]]
}
process_owned() {
    local command_line started recorded
    [[ -f "$IDENTITY_FILE" ]] || return 1
    recorded="$(< "$IDENTITY_FILE")"
    started="$(LC_ALL=C ps -p "$APP_PID" -o lstart= 2>/dev/null)" || return 1
    command_line="$(LC_ALL=C ps -ww -p "$APP_PID" -o command= 2>/dev/null)" || return 1
    [[ -n "$recorded" && "$started" == "$recorded" &&
       "$command_line" == *" -Ddbcompanion.runtime=$RUN_DIR -jar $RUN_JAR --server.port="* ]]
}
clear_pid() {
    rm -f -- "$PID_FILE" "$IDENTITY_FILE" "$PORT_FILE"
    APP_PID=""
}
read_pid() {
    [[ -f "$PID_FILE" ]] || return 0
    APP_PID="$(< "$PID_FILE")"
    [[ "$APP_PID" =~ ^[1-9][0-9]*$ && ${#APP_PID} -le 9 && "$APP_PID" -gt 1 ]] || fail "잘못된 PID 파일입니다. 프로세스를 종료하지 않았습니다: $PID_FILE"
    if ! process_alive "$APP_PID"; then
        printf '이전 PID %s는 종료되어 기록만 정리합니다.\n' "$APP_PID"
        clear_pid
    elif ! process_owned; then
        fail "PID ${APP_PID}가 기록된 DB Companion 프로세스와 일치하지 않습니다. 종료하거나 PID 파일을 지우지 않았습니다."
    fi
}
validate_timeout() {
    [[ "$2" =~ ^[1-9][0-9]*$ && ${#2} -le 3 && "$2" -le 300 ]] || fail "$1 값은 1~300초여야 합니다."
}
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-45}"
validate_timeout SHUTDOWN_TIMEOUT "$SHUTDOWN_TIMEOUT"

prepare_start() {
    local version jar_candidates
    STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-45}"
    validate_timeout STARTUP_TIMEOUT "$STARTUP_TIMEOUT"
    APP_PORT="${PORT:-8080}"
    if [[ "$ACTION" == restart && -z "${PORT:-}" && -f "$PORT_FILE" ]]; then
        APP_PORT="$(< "$PORT_FILE")"
    fi
    [[ "$APP_PORT" =~ ^[1-9][0-9]*$ && ${#APP_PORT} -le 5 && "$APP_PORT" -le 65535 ]] || fail 'PORT 값은 1~65535여야 합니다.'
    command -v curl >/dev/null || fail 'curl이 필요합니다.'
    command -v lsof >/dev/null || fail '포트/프로세스 확인에 lsof가 필요합니다.'
    shopt -s nullglob
    jar_candidates=("$APP_ROOT"/target/db-manage-companion-*.jar)
    [[ ${#jar_candidates[@]} == 1 ]] || fail 'target에 실행 JAR이 정확히 하나 있어야 합니다. mvn clean package로 빌드해 주세요.'
    SOURCE_JAR="${jar_candidates[0]}"
    [[ -s "$SOURCE_JAR" ]] || fail "비어 있는 JAR입니다: $SOURCE_JAR"
    if [[ -n "${JAVA_HOME:-}" ]]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    elif [[ -s "$JAVA_FILE" ]]; then
        JAVA_BIN="$(< "$JAVA_FILE")"
    else
        JAVA_BIN="$(command -v java)" || fail 'Java 21 이상을 설치하고 JAVA_HOME을 지정해 주세요.'
    fi
    [[ "$JAVA_BIN" == /* && -x "$JAVA_BIN" ]] || fail '유효한 JAVA_HOME을 지정해 주세요. Java 실행 파일을 찾을 수 없습니다.'
    version="$("$JAVA_BIN" -version 2>&1)" || fail 'Java 실행에 실패했습니다. Java 21 이상의 JAVA_HOME을 지정해 주세요.'
    [[ "$version" =~ version\ \"([0-9]+) && "${BASH_REMATCH[1]}" -ge 21 ]] || fail 'Java 21 이상이 필요합니다.'
}
stop_app() {
    local deadline
    read_pid
    if [[ -z "$APP_PID" ]]; then
        printf '%s\n' '관리 중인 실행 PID가 없습니다. 다른 프로세스는 종료하지 않습니다.'
        return 0
    fi
    process_owned || fail '종료 직전 프로세스 검증에 실패했습니다.'
    printf 'DB Companion PID %s에 정상 종료를 요청합니다.\n' "$APP_PID"
    kill -TERM "$APP_PID" || fail '종료 요청에 실패했습니다. PID 기록을 보존합니다.'
    deadline=$((SECONDS + SHUTDOWN_TIMEOUT))
    while process_alive "$APP_PID"; do
        if ! process_owned; then
            process_alive "$APP_PID" || break
            fail '종료 대기 중 PID의 소유 프로세스가 달라졌습니다. 추가 신호를 보내지 않습니다.'
        fi
        (( SECONDS < deadline )) || fail "종료 제한 ${SHUTDOWN_TIMEOUT}초를 초과했습니다. 강제 종료하지 않으며 PID 기록을 보존합니다."
        sleep 1
    done
    clear_pid
    printf '%s\n' '종료 완료. PID 기록을 삭제했습니다.'
}
start_app() {
    local command_line processes deadline http_code
    read_pid
    [[ -z "$APP_PID" ]] || fail "이미 실행 중입니다 (PID $APP_PID). 재기동하려면 ./restart.sh를 사용하세요."
    # A missing PID file must not let us overwrite an orphaned instance's JAR.
    processes="$(LC_ALL=C ps -axww -o command=)" || fail '프로세스 목록을 확인할 수 없습니다.'
    while IFS= read -r command_line; do
        [[ "$command_line" != *" -Ddbcompanion.runtime=$RUN_DIR -jar $RUN_JAR --server.port="* ]] || fail 'PID 파일 없이 실행 중인 이 앱이 있습니다. JAR을 교체하지 않았습니다.'
    done <<< "$processes"
    if lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "포트 ${APP_PORT}가 이미 사용 중입니다. 기존 프로세스를 자동 종료하지 않습니다."
    fi
    mkdir -p "$APP_ROOT/logs"
    cp -- "$SOURCE_JAR" "$RUN_DIR/app.jar.next"
    mv -- "$RUN_DIR/app.jar.next" "$RUN_JAR"
    printf '\n--- startup %s port=%s ---\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" "$APP_PORT" >> "$LOG_FILE"
    # Give the daemon its own process group, including when launched by a noninteractive runner.
    set -m
    nohup "$JAVA_BIN" "-Ddbcompanion.runtime=$RUN_DIR" -jar "$RUN_JAR" "--server.port=$APP_PORT" >> "$LOG_FILE" 2>&1 < /dev/null &
    APP_PID=$!
    disown "$APP_PID"
    set +m
    printf '%s\n' "$APP_PID" > "$PID_FILE"
    LC_ALL=C ps -p "$APP_PID" -o lstart= > "$IDENTITY_FILE" || true
    printf '%s\n' "$APP_PORT" > "$PORT_FILE"
    printf '%s\n' "$JAVA_BIN" > "$JAVA_FILE"
    deadline=$((SECONDS + STARTUP_TIMEOUT))
    while (( SECONDS < deadline )); do
        if ! process_alive "$APP_PID"; then
            clear_pid
            fail "앱이 기동 중 종료됐습니다. 로그: $LOG_FILE"
        fi
        if process_owned && lsof -nP -a -p "$APP_PID" -iTCP:"$APP_PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
            http_code="$(curl --noproxy '*' --silent --output /dev/null --write-out '%{http_code}' --max-time 2 "http://127.0.0.1:$APP_PORT/login")" || http_code='000'
            if [[ "$http_code" == 200 ]]; then
                printf '기동 완료: http://127.0.0.1:%s/login (PID %s)\n로그: %s\n' "$APP_PORT" "$APP_PID" "$LOG_FILE"
                return 0
            fi
        fi
        sleep 1
    done
    printf '기동 확인 제한 %s초 초과. 이번에 시작한 앱을 정상 종료합니다.\n' "$STARTUP_TIMEOUT" >&2
    stop_app
    fail "기동하지 못했습니다. 로그: $LOG_FILE"
}

case "$ACTION" in
    start) read_pid; [[ -z "$APP_PID" ]] || fail "이미 실행 중입니다 (PID $APP_PID)."; prepare_start; start_app ;;
    stop) stop_app ;;
    restart) prepare_start; stop_app; start_app ;;
esac
