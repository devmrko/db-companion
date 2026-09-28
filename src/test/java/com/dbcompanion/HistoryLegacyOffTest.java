package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.MetadataHistoryService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class HistoryLegacyOffTest {
    final Target target = new Target("APP", "T", null);

    @Test void bothKnownTableTriggerVersionsCanBeDisabledAndNeverAutoReplaced() {
        for (boolean v1 : new boolean[]{true, false}) {
            var fixture = new ViewMetadataServiceTest();
            var operations = new ArrayList<String>();
            var disabled = new boolean[]{false};
            var jdbc = new JdbcTemplate() {
                @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                    return List.of(type.cast(disabled[0] ? "DISABLED" : "ENABLED"));
                }
            };
            var repository = new MetadataHistoryRepository(jdbc) {
                @Override public String source(HistorySql.Asset asset) {
                    boolean before = asset.name().equals(HistorySql.triggerName(target, true));
                    return (v1 ? HistorySql.legacyTrigger(target, "INSTALLER", before) : HistorySql.tableTriggerV2(target, "INSTALLER", before)).sql();
                }
                @Override public void requireValid(String owner, HistorySql.Asset asset) { }
                @Override public State state(Target t) { return new State(true, !disabled[0], false, "legacy", "B", "A", "INSTALLER", true, ""); }
                @Override public void setEnabled(Target t, String owner, boolean enabled, boolean legacy) {
                    assertThat(enabled).isFalse(); disabled[0] = true; operations.add("OFF");
                }
                @Override public void execute(String sql) { operations.add(sql); }
            };
            var readiness = new HistoryReadinessRepository(jdbc, repository) {
                @Override public Preparation prepare(Target t, boolean enabling, boolean validate) {
                    return new Preparation(new Configuration(true, false, !disabled[0], "INSTALLER"), "INSTALLER", List.of(), null,
                            new HistoryPermissions.Decision(true, List.of()), false, repository.legacyTriggers(target, "INSTALLER"), true);
                }
            };
            for (boolean before : new boolean[]{true, false}) {
                var asset = HistorySql.trigger(target, "INSTALLER", before);
                assertThat(repository.validateKnownTrigger(target, asset, false)).isEqualTo(HistorySql.TriggerVersion.LEGACY);
                assertThatThrownBy(() -> repository.validateKnownTrigger(target, asset, true)).isInstanceOf(MetadataEditException.class);
            }
            try (var session = new OntologyReadCacheTest().session()) {
                var service = new MetadataHistoryService(fixture.source, fixture.metadata, repository, readiness);
                assertThatThrownBy(() -> service.toggle(session, target, true)).isInstanceOf(MetadataEditException.class);
                assertThatThrownBy(() -> service.upgradeCode(session, target)).isInstanceOf(MetadataEditException.class);
                assertThat(operations).isEmpty();
                assertThat(service.toggle(session, target, false).enabled()).isFalse();
                assertThat(operations).containsExactly("OFF", HistorySql.switchTrigger(target, "INSTALLER", true, false), HistorySql.switchTrigger(target, "INSTALLER", false, false));
                for (boolean before : new boolean[]{true, false})
                    assertThat(repository.validateKnownTrigger(target, HistorySql.trigger(target, "INSTALLER", before), true)).isEqualTo(HistorySql.TriggerVersion.LEGACY);
            }
        }
    }
}
