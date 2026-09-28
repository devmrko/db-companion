package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.repository.DatabaseRepository;
import com.dbcompanion.service.DatabaseService.TableDetail;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import static org.assertj.core.api.Assertions.*;

class ViewCatalogTest {
    static class CatalogJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<List<Object>> bindings = new ArrayList<>();
        void remember(String sql, Object[] args) { sqls.add(sql); bindings.add(Arrays.asList(args)); }
        @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            remember(sql,args);
            List<String> values;
            if (sql.contains("ALL_VIEWS")) values = "V_ONLY".equals(args[1]) ? List.of("V_ONLY") : List.of();
            else if (sql.contains("ALL_TAB_COMMENTS")) values = List.of("view comment");
            else if (args.length == 1) values = List.of("T_ONLY", "V_ONLY");
            else values = "MISSING".equals(args[1]) ? List.of() : List.of((String)args[1]);
            return values.stream().map(type::cast).toList();
        }
        @Override public void query(String sql, RowCallbackHandler handler, Object... args) {
            remember(sql,args);
            for (var row : List.of(List.of("T_ONLY","table comment"),List.of("V_ONLY","view comment"))) {
                var result = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                        (proxy,method,values) -> row.get((int)values[0]-1));
                try { handler.processRow(result); } catch (java.sql.SQLException ex) { throw new AssertionError(ex); }
            }
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            remember(sql,args); return List.of();
        }
    }

    @Test void catalogIncludesViewsAndCommentsWithoutBroadeningToSynonymsOrOtherObjects() {
        var jdbc = new CatalogJdbc(); var repository = new DatabaseRepository(jdbc);
        assertThat(repository.tables("Owner'Name")).containsExactly(new TableInfo("T_ONLY","table comment"),new TableInfo("V_ONLY","view comment"));
        assertThat(jdbc.sqls.getFirst()).contains("DISTINCT OBJECT_NAME", "SUBOBJECT_NAME IS NULL", "('TABLE', 'VIEW')").doesNotContain("SYNONYM", "Owner'Name");
        assertThat(jdbc.sqls.getLast()).contains("TABLE_TYPE IN ('TABLE', 'VIEW')");
        assertThat(jdbc.bindings).containsExactly(List.of("Owner'Name"),List.of("Owner'Name"));
    }

    @Test void viewDetailAndMissingObjectStayOwnerBound() {
        var jdbc = new CatalogJdbc(); var repository = new DatabaseRepository(jdbc);
        assertThat(repository.table("LAB", "V_ONLY")).isEqualTo(new TableInfo("V_ONLY", "view comment"));
        assertThat(jdbc.bindings).containsExactly(List.of("LAB","V_ONLY"),List.of("LAB","V_ONLY"));
        assertThat(repository.table("LAB", "MISSING")).isNull();
        assertThat(jdbc.sqls).hasSize(3);
    }

    @Test void annotationsUseActualViewTypeAndKeepTableTypeForTables() {
        var jdbc = new CatalogJdbc(); var repository = new DatabaseRepository(jdbc);
        repository.annotations("LAB", "V_ONLY");
        assertThat(jdbc.bindings.getLast()).containsExactly("LAB", "V_ONLY", "VIEW");
        repository.annotations("APP", "T_ONLY");
        assertThat(jdbc.bindings.getLast()).containsExactly("APP", "T_ONLY", "TABLE");
    }

    String render(String tab, boolean editable) {
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, Map<String,Object> parameters) { return ""; }
        });
        var context = new Context(Locale.ENGLISH);
        context.setVariables(Map.of("info", Map.of("username","APP"), "_csrf", Map.of("token","token","headerName","X-CSRF","parameterName","_csrf"),
                "selectedTab",tab,"targetSchema","APP","targetTable","V_ONLY","selectedSchema","APP","schemas",List.of("APP")));
        context.setVariable("detail", new TableDetail(null, new TableInfo("V_ONLY","view comment"),
                List.of(new ColumnInfo(1,"ID","NUMBER","N","identifier")),
                List.of(new AnnotationInfo(null,"NOTE","value",null,null)),null,List.of(),List.of(),true,editable));
        return engine.process("table-detail", context);
    }

    @Test void viewTabsSupportCommentHistoryWhileAnnotationsRemainReadOnly() {
        for (var tab : List.of("columns","comments","annotations","constraints","indexes")) {
            assertThat(render(tab,false)).as(tab).contains("data-view-comments", "data-metadata-editor", "data-metadata-history", "data-annotation-editable=\"false\"", "data-object-label=\"View\"")
                    .doesNotContain("data-edit-metadata=\"annotation\"", "??catalog.");
        }
        assertThat(render("comments",false)).contains("view comment", "identifier", "data-edit-metadata=\"comment\"", "View comment");
        assertThat(render("annotations",false)).contains("NOTE", "value");
    }

    @Test void existingTablesRetainEditingAndHistory() {
        for (var tab : List.of("comments","annotations")) {
            assertThat(render(tab,true)).contains("data-edit-metadata", "data-metadata-editor", "data-metadata-history")
                    .doesNotContain("data-view-comments");
        }
    }
}
