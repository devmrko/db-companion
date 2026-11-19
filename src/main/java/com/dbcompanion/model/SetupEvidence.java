package com.dbcompanion.model;

import java.util.List;

/** Saved metadata is not evidence of execution success or business correctness. */
public final class SetupEvidence {
    private SetupEvidence() {}
    public record Item(String id, String at, String kind) {}
    public record Group(String source, String state, List<Item> items) {
        public Group { items = List.copyOf(items); }
    }
}
