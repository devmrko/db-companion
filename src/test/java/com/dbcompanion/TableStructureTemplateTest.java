package com.dbcompanion;

import com.dbcompanion.model.TableStructure.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual template fragment rendering with value objects; no simulated DB or login. */
class TableStructureTemplateTest {
    private String render(String fragment, List<?> items) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        var context = new Context(java.util.Locale.KOREAN); context.setVariable("items", items);
        return engine.process("fragments/table-structure", Set.of(fragment), context);
    }

    @Test void foreignKeyPreservesCompositeColumnOrderAndTarget() {
        var constraint = new Constraint("FK_ORDER", "R", List.of("TENANT_ID", "ORDER_ID"),
                "PARENT", "PK_ORDER", "ORDERS", List.of("TENANT_ID", "ID"), null, "CASCADE",
                "ENABLED", "VALIDATED", "NOT DEFERRABLE", "IMMEDIATE", null, null);
        var html = render("constraints", List.of(constraint));
        assertThat(html).contains("FK_ORDER", "FK", "PARENT.ORDERS", "PARENT.PK_ORDER", "CASCADE", "VALIDATED");
        assertThat(html.indexOf(">TENANT_ID<")).isLessThan(html.indexOf(">ORDER_ID<"));
        assertThat(html).doesNotContain("th:text=");
    }

    @Test void inaccessibleReferenceAndCheckSourceAreNotMisrepresented() {
        var missing = new Constraint("FK", "R", List.of("ID"), "OTHER", "PK", null, List.of(), null,
                "NO ACTION", "DISABLED", "NOT VALIDATED", "DEFERRABLE", "DEFERRED", null, null);
        var check = new Constraint("CK", "C", List.of("VALUE"), null, null, null, List.of(), "VALUE < 10",
                null, "ENABLED", "VALIDATED", "NOT DEFERRABLE", "IMMEDIATE", null, null);
        assertThat(render("constraints", List.of(missing, check))).contains("참조 테이블을 조회할 수 없습니다.",
                "VALUE &lt; 10", "CHECK / NOT NULL", "DEFERRED");
    }

    @Test void indexesShowExpressionDirectionUniquenessAndPartitionCaveat() {
        var index = new Index("APP", "IX_LOWER", "FUNCTION-BASED NORMAL", "NONUNIQUE", "N/A", "VISIBLE", "YES",
                List.of(new IndexColumn(1, "SYS_NC001$", "DESC", "LOWER(\"NAME\")")));
        assertThat(render("indexes", List.of(index))).contains("LOWER", "DESC", "NONUNIQUE", "파티션별 상태는 별도");
        assertThat(render("indexes", List.of())).contains("등록된 인덱스가 없습니다.");
        assertThat(render("constraints", List.of())).contains("등록된 제약조건이 없습니다.");
    }
}
