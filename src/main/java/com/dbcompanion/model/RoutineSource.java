package com.dbcompanion.model;

import java.util.List;

public record RoutineSource(String reference, List<Definition> definitions) {
    public RoutineSource { definitions=List.copyOf(definitions); }
    public record Definition(String owner, String object, String member, String type, List<Section> sections) {
        public Definition { sections=List.copyOf(sections); }
    }
    public record Section(String type, String text) {}
}
