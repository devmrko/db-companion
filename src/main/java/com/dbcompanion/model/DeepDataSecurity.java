package com.dbcompanion.model;

import java.util.*;

public final class DeepDataSecurity {
    private DeepDataSecurity() {}
    public enum Kind { roles, assignments, grants, applications }
    public record Dataset(String source, List<String> columns, List<Map<String,String>> rows,
                          String status, String error, String observedAt) {
        public Dataset {
            columns=List.copyOf(columns);
            rows=rows.stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
        public boolean cacheable(){return status.equals("AVAILABLE")||status.equals("ACCESS_REQUIRED");}
    }
    public record Section(String kind, Dataset data) {}
    public record Detail(List<Section> sections) { public Detail {sections=List.copyOf(sections);} }
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
}
