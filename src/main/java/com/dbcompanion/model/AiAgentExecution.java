package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class AiAgentExecution {
    private AiAgentExecution() {}
    public static final int PAGE_SIZE = 10;
    public static final Set<String> STATES = Set.of("RUNNING", "WAITING_FOR_HUMAN", "RESUMING", "SUCCEEDED", "FAILED");

    public record Query(LocalDate from, LocalDate to, String team, String state, int page) {
        public Query {
            var validated = new AiExecutionHistory.Query(from, to, team, "", page);
            team = validated.profile();
            state = state == null ? "" : state.strip();
            if (!state.isEmpty() && !STATES.contains(state)) throw new IllegalArgumentException("Invalid execution state");
        }
        public static Query parse(String from, String to, String team, String state, String page, LocalDate today) {
            var dates = AiExecutionHistory.Query.parse(from, to, team, "", page, today);
            return new Query(dates.from(), dates.to(), dates.profile(), state, dates.page());
        }
        public int offset() { return (page - 1) * PAGE_SIZE; }
    }
    public static String requireRunId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid team execution ID");
        return value;
    }
    public static int requirePage(int page) {
        if (page < 1 || page > 1000) throw new IllegalArgumentException("Invalid execution page");
        return page;
    }
    public static long requireOrder(long order) {
        if (order < 0 || order > 9007199254740991L) throw new IllegalArgumentException("Invalid task order");
        return order;
    }
    public static String stateLabel(String state) {
        if (state == null) return "—";
        return switch (state) {
            case "RUNNING" -> UiMessages.text("ui.1e6771997e8b", "실행 중");
            case "WAITING_FOR_HUMAN" -> UiMessages.text("ui.15be6ee44b15", "입력 대기");
            case "RESUMING" -> UiMessages.text("ui.63b051ba7563", "재개 중");
            case "SUCCEEDED" -> UiMessages.text("ui.727333ab0740", "완료");
            case "FAILED" -> UiMessages.text("ui.2743911f83e1", "실패");
            default -> state;
        };
    }
    public static String duration(java.math.BigDecimal seconds) {
        return seconds == null || seconds.signum() < 0 ? "—" : seconds.stripTrailingZeros().toPlainString() + UiMessages.text("ui.1fd639e1ec96", "초");
    }
    public record InputPreview(String text, boolean clipped, boolean ambiguous) {
        public static InputPreview of(String value, boolean ambiguous) {
            if (ambiguous) return new InputPreview(null, false, true);
            if (value == null) return new InputPreview(null, false, false);
            boolean clipped = value.codePointCount(0, value.length()) > 500;
            return new InputPreview(clipped ? value.substring(0, value.offsetByCodePoints(0, 500)) : value, clipped, false);
        }
        public String getDisplay() {
            if (ambiguous) return UiMessages.text("ui.84dd6e381dca", "확인 필요");
            return text == null || text.isBlank() ? "—" : text + (clipped ? "…" : "");
        }
        public String getTitle() {
            if (ambiguous) return UiMessages.text("ui.6617dc09d224", "첫 Task 순번이 중복되어 입력을 구분할 수 없습니다.");
            if (text == null || text.isBlank()) return UiMessages.text("ui.ba939a73b4eb", "첫 Task 입력이 없습니다.");
            return text + (clipped ? UiMessages.text("ui.5667056e1227", "…\n앞 500자입니다. 전체 내용은 Task 상세에서 확인하세요.") : "");
        }
    }
    public record Run(String id, String team, String state, String started, String ended, String duration, InputPreview preview) {
        public Run(String id, String team, String state, String started, String ended, String duration) {
            this(id, team, state, started, ended, duration, InputPreview.of(null, false));
        }
        public Run withPreview(InputPreview value) { return new Run(id, team, state, started, ended, duration, value); }
        public String getStateLabel() { return stateLabel(state); }
    }
    public record Task(long order, String task, String agent, String state, String started, String ended) {
        public String getStateLabel() { return stateLabel(state); }
    }
    public record Page<T>(List<T> items, int number, boolean hasNext) {
        public static <T> Page<T> of(List<T> rows, int page) {
            requirePage(page);
            return new Page<>(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), page, rows.size() > PAGE_SIZE);
        }
    }
    public record RunDetail(Run run, Page<Task> tasks) {}
    public record TaskDetail(String runId, long order, String task, String agent, String state,
                             String started, String ended, String input, String result, String conversationId) {}
    public record Conversations(String conversationId, Page<AiExecutionHistory.Item> prompts) {}
}
