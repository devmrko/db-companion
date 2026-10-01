package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.HistoryAccess.*;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.HistoryAccessService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class HistoryAccessTest {
    final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    final Target target=new Target("APP","T",null);
    final State state=new State(true,true,true,"ON","B","A","MANAGER",false,"");
    Preview preview() { return new Preview("token","APP","P","[]",now,List.of(new Item("T",state,List.of())),List.of("READER")); }
    @Test void expandsSchemaAndDeduplicatesObjectsWithoutTrackingItsOwnAssets() {
        assertThat(HistoryAccessService.targets("APP","[{\"owner\":\"APP\"},{\"owner\":\"APP\",\"name\":\"T\"}]",List.of("T","V","DBC_METADATA_HISTORY","DBC_MH_ACCESS")))
            .containsExactly("T","V");
    }
    @Test void foreignUnknownMalformedOrOversizedScopesNeverBecomePartialSuccess() {
        for(String raw:List.of("[]","{}","null","[{\"owner\":\"OTHER\",\"name\":\"T\"}]","[{\"owner\":\"APP\",\"name\":\"UNKNOWN\"}]","[{\"owner\":\"APP\",\"name\":null}]"))
            assertThatThrownBy(()->HistoryAccessService.targets("APP",raw,List.of("T"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->HistoryAccessService.targets("APP","[{\"owner\":\"APP\"}]",java.util.stream.IntStream.range(0,51).mapToObj(n->"T"+n).toList())).isInstanceOf(RuntimeException.class);
    }
    @Test void acceptsOnlyFreshPreviewAndItsSelectedTargets() {
        var request=new Apply("token",List.of("T"),Operation.READ,List.of("READER"),false);
        assertThatCode(()->HistoryAccessService.validate(preview(),request,now)).doesNotThrowAnyException();
        assertThatThrownBy(()->HistoryAccessService.validate(preview(),request,now.plusSeconds(601))).isInstanceOf(RuntimeException.class);
        for(var invalid:List.of(new Apply("wrong",List.of("T"),Operation.READ,List.of("READER"),false),
                new Apply("token",List.of("OTHER"),Operation.READ,List.of("READER"),false),
                new Apply("token",List.of("T","T"),Operation.READ,List.of("READER"),false),
                new Apply("token",List.of("T"),Operation.READ,List.of("PUBLIC"),false),
                new Apply("token",List.of("T"),Operation.READ,List.of("READER"),true),
                new Apply("token",List.of("T"),Operation.READ,List.of(),false)))
            assertThatThrownBy(()->HistoryAccessService.validate(preview(),invalid,now)).isInstanceOf(RuntimeException.class);
    }
    @Test void publicIsAnExplicitChoiceAndSwitchingNeedsNoUserList() {
        assertThatCode(()->HistoryAccessService.validate(preview(),new Apply("token",List.of("T"),Operation.READ,List.of(),true),now)).doesNotThrowAnyException();
        assertThatCode(()->HistoryAccessService.validate(preview(),new Apply("token",List.of("T"),Operation.ENABLE,List.of(),false),now)).doesNotThrowAnyException();
    }
    @Test void scopedPackageHasNoCallerSuppliedSqlOrGrantAuthorityAndKeepsAllChangers() {
        var assets=HistoryAccessSql.assets("MANAGER");
        assertThat(assets).hasSize(4);
        var spec=assets.get(2).sql();var body=assets.get(3).sql();
        assertThat(spec).contains("AUTHID DEFINER","FUNCTION inspect","FUNCTION entries","PROCEDURE set_enabled").doesNotContain("p_sql","GRANT");
        assertThat(body).contains("SESSION_USER","CASE WHEN GRANTEE = 'PUBLIC' THEN 1 ELSE 0 END","-20086","DBMS_LOB.COMPARE","SQL%ROWCOUNT <> 1");
        assertThat(body).doesNotContain("CREATE TRIGGER","GRANT ","AUTONOMOUS_TRANSACTION");
        assertThat(HistorySql.auditBody(target,false).sql()).doesNotContain("DBC_MH_ACCESS","CAN_READ","CAN_MANAGE");
    }
    @Test void gatewayOwnerIsNotHardCodedAndGrantTargetsCannotInjectSql() {
        var assets=HistoryAccessSql.assets("Odd\"Owner");
        assertThat(assets.get(0).sql()).contains("\"Odd\"\"Owner\".");
        assertThat(HistoryAccessSql.grantee("PUBLIC")).isEqualTo("PUBLIC");
        assertThat(HistoryAccessSql.grantee("X\"; DROP USER Y")).isEqualTo("\"X\"\"; DROP USER Y\"");
    }
    @Test void verifiedInstallerStatusWorksWithoutCatalogTriggerVisibilityAndWithoutManagementRights() {
        var jdbc=new JdbcTemplate() {
            @Override public <T>T queryForObject(String sql,Class<T> type,Object...args) {
                if(sql.contains("ALL_OBJECTS"))return type.cast(1);
                if(sql.contains(".inspect"))return type.cast("{\"owner\":\"MANAGER\",\"schema\":\"APP\",\"table\":\"T\",\"enabled\":\"Y\",\"healthy\":1,\"canRead\":1,\"canManage\":0,\"beforeTrigger\":\""+HistorySql.triggerName(target,true)+"\",\"afterTrigger\":\""+HistorySql.triggerName(target,false)+"\"}");
                throw new AssertionError(sql);
            }
            @Override public <T>T queryForObject(String sql,Class<T> type) { return type.cast("APP"); }
        };
        var history=new MetadataHistoryRepository(jdbc) {
            @Override public Configuration configuration(Target t,boolean validate) {return new Configuration(true,false,true,"MANAGER");}
        };
        var result=history.delegatedState(target);
        assertThat(result.installed()).isTrue();assertThat(result.healthy()).isTrue();assertThat(result.enabled()).isTrue();assertThat(result.canManage()).isFalse();
        assertThat(result.setup().nextStep()).isEqualTo(SetupStep.NONE);
        assertThatThrownBy(()->history.delegatedToggle(target,false)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);
    }
    @Test void malformedOrWrongTargetInspectionNeverGrantsTrust() {
        var jdbc=new JdbcTemplate() {
            @Override public <T>T queryForObject(String sql,Class<T> type,Object...args) {return type.cast("{\"owner\":\"OTHER\",\"schema\":\"APP\",\"table\":\"T\"}");}
        };
        assertThatThrownBy(()->new HistoryAccessRepository(jdbc).inspect("MANAGER",target)).isInstanceOf(RuntimeException.class);
    }
}
