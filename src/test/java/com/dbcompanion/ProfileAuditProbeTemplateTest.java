package com.dbcompanion;

import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import com.dbcompanion.service.ProfileAuditProbeService.Report;
import com.dbcompanion.service.ProfileAuditProbeService.InstructionAttempt;
import com.dbcompanion.common.db.InstructionAuditProbe;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileAuditProbeTemplateTest {
    @Test void rawAuditTextIsEscapedAndPartialWorkIsNotLabeledSuccess() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        var row = new LinkedHashMap<String, String>(); row.put("SQL_TEXT", "<script>unsafe</script>"); row.put("SQL_BINDS", null);
        var context = new Context(java.util.Locale.KOREAN);
        context.setVariable("result", new Report(new Run("0123456789ABCDEF"), null,
                List.of("ORA-01031: insufficient privileges"), List.of(), List.of(row), List.of("AUDIT POLICY remaining"), false));
        var rendered = engine.process("profile-audit-probe", Set.of("result"), context);
        assertThat(rendered).contains("&lt;script&gt;", "(NULL)", "false", "AUDIT POLICY remaining", "ORA-01031");
        assertThat(rendered).doesNotContain("<script>", "th:text=");
    }
    @Test void instructionResultsDistinguishActualStateFromMissingAuditText() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        var input = new InstructionAuditProbe.Input("CLOB_SHORT", InstructionAuditProbe.Transport.CLOB, "<script>한글</script>");
        var context = new Context(java.util.Locale.KOREAN);
        context.setVariable("result", new Report(new Run("0123456789ABCDEF"), null, List.of(), List.of(),
                List.of(), List.of(), true, List.of(new InstructionAttempt(input, input.value(), null))));
        var rendered = engine.process("profile-audit-probe", Set.of("result"), context);
        assertThat(rendered).contains("DB 반영 확인", "감사 본문 일치", "본문", "&lt;script&gt;", "추출되지 않음", "UTF-8");
        assertThat(rendered).doesNotContain("<script>");
    }
}
