package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import com.dbcompanion.repository.MetadataRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MetadataService {
    private final SessionDataSource source;
    private final MetadataRepository repository;
    private final com.dbcompanion.repository.HistoryReadinessRepository readiness;
    private final com.dbcompanion.repository.MetadataHistoryRepository history;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    public MetadataService(SessionDataSource source, MetadataRepository repository,
            com.dbcompanion.repository.HistoryReadinessRepository readiness,
            com.dbcompanion.repository.MetadataHistoryRepository history) {
        this.source = source; this.repository = repository;
        this.readiness = readiness;
        this.history = history;
        var manager = new DataSourceTransactionManager(source);
        read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(10);
        write = new TransactionTemplate(manager); write.setTimeout(10);
    }

    public java.util.Map<String, Object> historyReadiness(PoolSession session, Target target) {
        synchronized (session) {
            requireScope(session, target);
            source.bind(session.pool(), session.connectionSchema());
            try { return read.execute(status -> { repository.requireTarget(target); return readiness.readiness(target); }); }
            finally { source.clear(); }
        }
    }

    public Form edit(PoolSession session, Target target, String kind, String name) {
        synchronized (session) {
            requireScope(session, target); requireKind(kind);
            source.bind(session.pool(), session.connectionSchema());
            try {
                return read.execute(status -> {
                    repository.requireTarget(target);
                    boolean add = kind.equals("annotation") && (name == null || name.isEmpty());
                    if (add) return new Form(target, kind, "add", "", "", "", repository.columns(target));
                    var current = value(target, kind, name);
                    requireEditable(current, kind);
                    return new Form(target, kind, "edit", name, current.value(),
                            MetadataSql.version(target, kind, name, current), List.of());
                });
            } finally { source.clear(); }
        }
    }

    public SaveResult save(PoolSession session, SaveRequest request) {
        synchronized (session) {
            var target = request.target();
            requireScope(session, target); requireKind(request.kind());
            boolean add = request.kind().equals("annotation") && "add".equals(request.mode());
            if (!add && !"edit".equals(request.mode())) throw invalid(UiMessages.text("ui.21c68f82a413", "편집 모드를 확인해 주세요."));
            String name = request.kind().equals("comment") ? null
                    : add ? MetadataSql.newAnnotationName(request.name()) : request.name();
            String desired = MetadataSql.normalizedValue(request.value());
            String sql = request.kind().equals("comment") ? MetadataSql.comment(target, desired)
                    : MetadataSql.annotation(target, name, desired, add);
            var applied = new AtomicBoolean();
            source.bind(session.pool(), session.connectionSchema());
            try {
                return write.execute(status -> {
                    repository.requireTarget(target);
                    var before = value(target, request.kind(), name);
                    if (add) {
                        if (before.present()) throw new MetadataEditException(409, "Annotation already exists",
                                UiMessages.text("ui.a378d5c37dc4", "같은 이름의 Annotation이 있습니다. 해당 항목에서 편집해 주세요."));
                    } else {
                        requireEditable(before, request.kind());
                        MetadataSql.verifyVersion(target, request.kind(), name, before, request.version());
                        if (Objects.equals(MetadataSql.normalizedValue(before.value()), desired))
                            return new SaveResult(true, UiMessages.text("ui.e2b9c7bd1c43", "변경된 내용이 없습니다."));
                    }
                    history.requireHealthyIfTracked(target);
                    try { repository.execute(sql); applied.set(true); }
                    catch (DataAccessException ex) {
                        int code = sqlCode(ex);
                        // Never log DDL text or a metadata value.
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metadata DDL error: code={}", code);
                        if (uncertain(code)) return pending();
                        if (code == 1031) throw new MetadataEditException(403, "Insufficient metadata privileges",
                                UiMessages.text("ui.087bbf1e9e6c", "저장 권한이 없습니다. 코멘트는 소유자/COMMENT ANY TABLE, Annotation은 ALTER 권한이 필요합니다. (ORA-01031)"));
                        String detail = OracleErrorDetails.forDisplay(ex);
                        throw new MetadataEditException(400, "Metadata DDL rejected: " + code,
                                UiMessages.text("ui.b85bddd04b69", "DB가 변경을 거부했습니다. 권한·이름·값과 이력 트리거 상태를 확인해 주세요. (Oracle 오류 ") + code + ")"
                                        + (detail.isEmpty() ? "" : "\n" + detail));
                    }
                    try {
                        var after = value(target, request.kind(), name);
                        return after.present() && Objects.equals(MetadataSql.normalizedValue(after.value()), desired)
                                ? new SaveResult(true, UiMessages.text("ui.513ee667b349", "저장했습니다.")) : pending();
                    } catch (DataAccessException ex) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metadata readback error: code={}", sqlCode(ex));
                        return pending();
                    }
                });
            } catch (RuntimeException ex) {
                // Oracle metadata DDL commits implicitly. A later failure cannot roll it back.
                if (applied.get()) return pending();
                throw ex;
            } finally { source.clear(); }
        }
    }

    private Value value(Target target, String kind, String name) {
        return kind.equals("comment") ? repository.comment(target) : repository.annotation(target, name);
    }
    private void requireEditable(Value current, String kind) {
        if (!current.present()) throw new MetadataEditException(404, "Metadata no longer exists", UiMessages.text("ui.fbab1b57b3d7", "항목이 더 이상 존재하지 않습니다. 화면을 새로고침해 주세요."));
        if (kind.equals("annotation") && current.inherited()) throw new MetadataEditException(409,
                "Inherited annotation is read only", UiMessages.text("ui.c3bf7b46954f", "도메인에서 상속된 Annotation은 이 화면에서 편집할 수 없습니다."));
    }
    private void requireKind(String kind) {
        if (!"comment".equals(kind) && !"annotation".equals(kind)) throw invalid(UiMessages.text("ui.3e090af80a47", "코멘트 또는 Annotation만 편집할 수 있습니다."));
    }
    private void requireScope(PoolSession session, Target target) {
        if (!session.metadata().selectedSchema().equals(target.schema()))
            throw new MetadataEditException(409, "Selected schema changed", UiMessages.text("ui.dde600c5989c", "스키마가 변경되었습니다. 상세 화면을 다시 열어 주세요."));
    }
    private MetadataEditException invalid(String message) { return new MetadataEditException(400, "Invalid metadata request", message); }
    private SaveResult pending() {
        return new SaveResult(false, UiMessages.text("ui.3fc476a77622", "DB의 최종 반영 상태를 확인해야 합니다. 자동 재시도하지 말고 입력을 복사한 뒤 화면을 새로고침해 주세요."));
    }
    private int sqlCode(Throwable error) {
        while (error != null) {
            if (error instanceof SQLException sql) return sql.getErrorCode();
            error = error.getCause();
        }
        return 0;
    }
    private boolean uncertain(int code) { return java.util.Set.of(0, 1013, 3113, 3114, 3135, 17002, 17008, 17410).contains(code); }
}
