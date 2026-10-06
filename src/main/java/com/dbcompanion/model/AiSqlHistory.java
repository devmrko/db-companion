package com.dbcompanion.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Supplemental read-only sources. A row is not necessarily a single AI execution. */
public final class AiSqlHistory {
    private AiSqlHistory() {}
    public enum Source { cache, awr, audit }
    public enum Match { all, select_ai, generate }
    public record Query(Source source, LocalDate from, LocalDate to, String sqlId, String text, String actor, int page, Match match) {
        public Query(Source source, LocalDate from, LocalDate to, String sqlId, String text, String actor, int page) {
            this(source, from, to, sqlId, text, actor, page, Match.all);
        }
        public Query {
            if (source == null || match == null || from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 30)
                throw new IllegalArgumentException("Invalid date range");
            sqlId = sqlId == null ? "" : sqlId.strip(); text = text == null ? "" : text.strip(); actor = actor == null ? "" : actor.strip();
            if ((!sqlId.isEmpty() && !sqlId.matches("[a-z0-9]{13}")) || text.length() > 500 || actor.length() > 128 || page < 1 || page > 1000)
                throw new IllegalArgumentException("Invalid SQL history filter");
            if (source == Source.audit && !sqlId.isEmpty()) throw new IllegalArgumentException("Audit source has no SQL ID");
            if (source == Source.awr && !actor.isEmpty()) throw new IllegalArgumentException("AWR text has no execution user");
        }
        public static Query parse(String source, String from, String to, String id, String text, String actor, String page, LocalDate today) {
            return parse(source, from, to, id, text, actor, page, "all", today);
        }
        public static Query parse(String source, String from, String to, String id, String text, String actor, String page, String match, LocalDate today) {
            return new Query(Source.valueOf(source), from == null || from.isBlank() ? today.minusDays(6) : LocalDate.parse(from),
                    to == null || to.isBlank() ? today : LocalDate.parse(to), id, text, actor, Integer.parseInt(page), Match.valueOf(match));
        }
        public int offset() { return (page - 1) * 10; }
    }
    public record Item(String id, String time, String sqlId, String actor, String kind, String count, String preview, List<String> keys) {
        public Item { keys = List.copyOf(keys); }
    }
    public record Failure(int code, String details) {
        public boolean cancelled() { return code == 1013; }
    }
    public static final class AmbiguousRecord extends RuntimeException {}
    public static final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable(String message) { super(message); }
    }
    public record Page(List<Item> items, int number, boolean hasNext, Instant observed, Failure failure) {
        public Page { items = List.copyOf(items); }
        public static Page of(List<Item> rows, int number) {
            return new Page(rows.subList(0, Math.min(10, rows.size())), number, rows.size() > 10, Instant.now(), null);
        }
    }
    public record Field(String name, String value) {}
    public record Translation(String sqlId, String translated, String method, String sql) {}
    public record Mapping(String status, List<Translation> items, boolean more) {
        public Mapping { items = List.copyOf(items); }
    }
    public record Detail(List<Field> fields, String sql, String source, Mapping mapping) {
        public Detail(List<Field> fields, String sql) { this(fields, sql, "", null); }
    }
    public record SqlCandidate(String sqlId, String sql, List<String> objects, String lastActive,
            long offsetSeconds, String executions, String elapsedSeconds, String evidence) {
        public SqlCandidate { objects = List.copyOf(objects); }
    }
    public record Candidates(String anchorTime, String schema, String sessionEvidence, List<SqlCandidate> items,
            boolean limited, int omitted) {
        public Candidates { items = List.copyOf(items); }
    }
    public record Policies(List<List<Field>> rows, boolean more, Instant observed, Failure failure) {
        public Policies { rows = rows.stream().map(List::copyOf).toList(); }
    }
    public record Selection(Query query, Item item) {}
    public static final class State {
        private final LinkedHashMap<Query, Page> pages = new LinkedHashMap<>();
        private Policies policies;
        public synchronized Page page(Query query, Supplier<Page> loader) {
            if (!pages.containsKey(query)) {
                var loaded = loader.get();
                // A subsequent explicit search may retry a cancelled query; never retry automatically.
                if (loaded.failure() != null && loaded.failure().cancelled()) return loaded;
                pages.put(query, loaded);
                while (pages.size() > 12) pages.remove(pages.keySet().iterator().next());
            }
            return pages.get(query);
        }
        public synchronized Policies policies(Supplier<Policies> loader) {
            if (policies == null) policies = loader.get();
            return policies;
        }
        public synchronized void refresh(Source source) {
            pages.keySet().removeIf(q -> q.source() == source);
            if (source == Source.audit) policies = null;
        }
        public synchronized Selection selection(String id) {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid history selection");
            for (var entry : pages.entrySet()) for (var item : entry.getValue().items())
                if (item.id().equals(id)) return new Selection(entry.getKey(), item);
            return null;
        }
    }
}
