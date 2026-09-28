package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetadataRepository {
    private final JdbcTemplate jdbc;
    public MetadataRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireTarget(Target target) {
        var table = jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS o WHERE o.OWNER = ? AND o.OBJECT_NAME = ? "
                + "AND o.SUBOBJECT_NAME IS NULL AND o.OBJECT_TYPE IN ('TABLE', 'VIEW')",
                String.class, target.schema(), target.table());
        if (table.size() != 1) throw new MetadataEditException(404, "Table or view not accessible",
                UiMessages.text("metadata.target.unsupported", "테이블 또는 뷰가 없거나 조회 권한이 없습니다. Synonym과 다른 객체 종류는 지원하지 않습니다."));
        if (target.column() != null && jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, target.schema(), target.table(), target.column()).isEmpty())
            throw new MetadataEditException(404, "Column not accessible", UiMessages.text("ui.63cc5be8c7a6", "컬럼이 없거나 조회 권한이 없습니다."));
    }
    public void requireAnnotationTarget(Target target) {
        if (!jdbc.queryForList("SELECT VIEW_NAME FROM SYS.ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?",
                String.class, target.schema(), target.table()).isEmpty())
            throw new MetadataEditException(400, "View annotation editing is unsupported",
                    UiMessages.text("metadata.view.annotationUnsupported", "뷰 Annotation 편집은 지원하지 않습니다. 뷰와 컬럼 코멘트를 편집해 주세요."));
    }
    public List<String> columns(Target target) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID",
                String.class, target.schema(), target.table());
    }
    public Value comment(Target target) {
        var values = target.column() == null
                ? jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ? AND TABLE_TYPE IN ('TABLE', 'VIEW')",
                    String.class, target.schema(), target.table())
                : jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, target.schema(), target.table(), target.column());
        return new Value(true, values.isEmpty() ? null : values.getFirst(), false);
    }
    public Value annotation(Target target, String name) {
        String column = target.column() == null ? "COLUMN_NAME IS NULL" : "COLUMN_NAME = ?";
        Object[] args = target.column() == null ? new Object[]{target.schema(), target.table(), name}
                : new Object[]{target.schema(), target.table(), name, target.column()};
        var values = jdbc.query("SELECT ANNOTATION_VALUE, DOMAIN_NAME FROM SYS.ALL_ANNOTATIONS_USAGE "
                + "WHERE ANNOTATION_OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = 'TABLE' AND ANNOTATION_NAME = ? AND " + column,
                (row, n) -> new Value(true, row.getString(1), row.getString(2) != null), args);
        if (values.size() > 1) throw new MetadataEditException(409, "Ambiguous inherited annotation",
                UiMessages.text("ui.8fc202547a68", "동일한 이름의 Annotation 출처가 여러 개라 편집할 수 없습니다."));
        return values.isEmpty() ? new Value(false, null, false) : values.getFirst();
    }
    public void execute(String sql) { jdbc.execute(sql); }
}
