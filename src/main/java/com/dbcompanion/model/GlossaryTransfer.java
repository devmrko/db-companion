package com.dbcompanion.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Portable content only: source IDs/revisions never select a destination row. */
public final class GlossaryTransfer {
    public static final String FORMAT = "dbcompanion.business-glossary";
    public static final int MAX_BYTES = 4_000_000;
    private GlossaryTransfer() {}
    public record Document(String format, int version, List<BusinessGlossary.Draft> terms) {
        public Document {
            if (!FORMAT.equals(format) || version != 1 || terms == null || terms.size() > 500 || terms.stream().anyMatch(java.util.Objects::isNull)) throw BusinessGlossary.invalid();
            terms = List.copyOf(terms);
            var names = new HashSet<String>();
            for (var term : terms) if (!names.add(BusinessGlossary.normalize(term.term()))) throw BusinessGlossary.failure(400, "파일에 중복 대표 용어가 있습니다.");
        }
    }
    public record Entry(int row, BusinessGlossary.Draft value, String status, BusinessGlossary.Term previous) {}
    public record Preview(String token, String owner, Instant expires, List<Entry> entries) {}
    public record Apply(String token, List<Integer> rows, boolean consent) {}
    public static Preview preview(String owner, Document doc, List<BusinessGlossary.Term> current) {
        Map<String, List<BusinessGlossary.Term>> index = current.stream().collect(Collectors.groupingBy(t -> BusinessGlossary.normalize(t.term())));
        var entries = new ArrayList<Entry>();
        for (int row = 0; row < doc.terms().size(); row++) {
            var value = doc.terms().get(row);
            var matches = index.getOrDefault(BusinessGlossary.normalize(value.term()), List.of());
            var previous = matches.size() == 1 ? matches.getFirst() : null;
            String status = matches.size() > 1 ? "CONFLICT" : previous == null ? "NEW" : previous.draft().equals(value) ? "UNCHANGED" : "UPDATE";
            entries.add(new Entry(row, value, status, previous));
        }
        return new Preview(UUID.randomUUID().toString(), owner, Instant.now().plusSeconds(900), List.copyOf(entries));
    }
    public static List<Entry> selected(Preview preview, Apply request, String owner) {
        if (preview == null || request == null || !request.consent() || !preview.owner().equals(owner)
                || !preview.token().equals(request.token()) || preview.expires().isBefore(Instant.now())
                || request.rows() == null || request.rows().isEmpty() || request.rows().size() > 500
                || new HashSet<>(request.rows()).size() != request.rows().size()) throw BusinessGlossary.stale();
        var selected = new ArrayList<Entry>();
        for (Integer row : request.rows()) {
            if (row == null || row < 0 || row >= preview.entries().size()) throw BusinessGlossary.invalid();
            var entry = preview.entries().get(row);
            if (!List.of("NEW", "UPDATE").contains(entry.status())) throw BusinessGlossary.invalid();
            selected.add(entry);
        }
        return List.copyOf(selected);
    }
}
