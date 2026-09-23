package com.dbcompanion.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class AiExecutionHistory {
    private AiExecutionHistory() {}
    public static final int PAGE_SIZE = 10;
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Query(LocalDate from, LocalDate to, String profile, String question, int page) {
        public Query {
            if (from == null || to == null || from.getYear() < 1900 || to.getYear() > 9998
                    || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 30)
                throw new IllegalArgumentException("A date range of 1 to 31 days is required");
            profile = profile == null ? "" : profile.strip();
            question = question == null ? "" : question.strip();
            if (profile.length() > 128 || question.length() > 500 || page < 1 || page > 1000)
                throw new IllegalArgumentException("Invalid history filter or page");
        }
        public static Query parse(String from, String to, String profile, String question, String page, LocalDate today) {
            LocalDate end = to == null || to.isBlank() ? today : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(6) : LocalDate.parse(from);
            return new Query(start, end, profile, question, page == null || page.isBlank() ? 1 : Integer.parseInt(page));
        }
        public int offset() { return (page - 1) * PAGE_SIZE; }
    }

    public static String requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,36}")) throw new IllegalArgumentException("Invalid conversation prompt ID");
        return id;
    }
    public record Item(String id, String created, String profile, String action, String preview) {}
    public record Page(List<Item> items, int number, boolean hasNext) {
        public static Page of(List<Item> rows, int number) {
            return new Page(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), number, rows.size() > PAGE_SIZE);
        }
    }
    public record Detail(String id, String conversationId, String title, String profile, String action,
                         String created, String modified, String prompt, String response,
                         String clientIdentifier, String clientIp, String sid, String serial) {}
}
