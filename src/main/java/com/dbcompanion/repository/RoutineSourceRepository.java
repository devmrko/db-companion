package com.dbcompanion.repository;

import com.dbcompanion.model.RoutineSource;
import com.dbcompanion.model.RoutineSource.*;
import com.dbcompanion.service.SqlObjectName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class RoutineSourceRepository {
    private final JdbcTemplate jdbc;
    public RoutineSourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private record Target(String owner, String object, String member) {}

    public RoutineSource source(String schema, String reference) {
        return source(schema,reference,0);
    }
    public RoutineSource source(String schema, String reference, int maxChars) {
        var parts = SqlObjectName.parts(reference);
        var targets = switch (parts.size()) {
            case 1 -> List.of(new Target(schema, parts.get(0), null));
            case 2 -> List.of(new Target(parts.get(0), parts.get(1), null),
                    new Target(schema, parts.get(0), parts.get(1)));
            default -> List.of(new Target(parts.get(0), parts.get(1), parts.get(2)));
        };
        var definitions = new ArrayList<Definition>();
        int[] total={0};
        for (var target : targets) {
            boolean packaged = target.member() != null;
            String filter = packaged ? "OBJECT_TYPE = 'PACKAGE' AND PROCEDURE_NAME = ?"
                    : "OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE') AND PROCEDURE_NAME IS NULL";
            Object[] args = packaged ? new Object[]{target.owner(), target.object(), target.member()}
                    : new Object[]{target.owner(), target.object()};
            var types = jdbc.queryForList("SELECT DISTINCT OBJECT_TYPE FROM SYS.ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_NAME = ? AND "
                    + filter, String.class, args);
            if (types.isEmpty()) continue;
            var source = new LinkedHashMap<String, StringBuilder>();
            jdbc.query("SELECT TYPE, TEXT FROM SYS.ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND "
                    + (packaged ? "TYPE IN ('PACKAGE', 'PACKAGE BODY')" : "TYPE IN ('FUNCTION', 'PROCEDURE')")
                    + " ORDER BY TYPE, LINE", (RowCallbackHandler) row -> {
                    String text=row.getString(2);if(text==null)return;
                    total[0]+=text.length();
                    if(maxChars>0&&total[0]>maxChars)throw new com.dbcompanion.model.FunctionCatalog.Failure(422,
                            com.dbcompanion.common.i18n.UiMessages.text("functions.tooLarge","소스가 화면 조회 한도(2,000,000자)를 넘습니다. SQL 도구에서 확인해 주세요."));
                    source.computeIfAbsent(row.getString(1), k -> new StringBuilder()).append(text);
                    },
                    target.owner(), target.object());
            definitions.add(new Definition(target.owner(), target.object(), target.member(), types.getFirst(),
                    source.entrySet().stream().map(e -> new Section(e.getKey(), e.getValue().toString())).toList()));
        }
        return new RoutineSource(reference, List.copyOf(definitions));
    }
}
