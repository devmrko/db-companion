package com.dbcompanion;

import com.dbcompanion.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class VectorSearchTemplateTest {
    private String render(String table,String error) {
        return render(table,error,java.util.Locale.KOREAN);
    }
    private String render(String table,String error,java.util.Locale locale) {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,java.util.Map<String,Object> p){return "";}
        });
        var context=new Context(locale);context.setVariable("activePage","vectors");context.setVariable("selectedSchema","APP");
        context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));context.setVariable("schemas",List.of("APP"));
        context.setVariable("tableName",table);context.setVariable("loadError",error);
        if(table.isEmpty()&&error==null)context.setVariable("vectorTables",List.of(new VectorSearch.Table("ANY_TABLE","<img src=x> 스키마 日本語 原文",List.of("VECTOR_1","VECTOR_2"))));
        return engine.process("vector-search",context);
    }
    @Test void listEscapesMetadataAndHasSchemaReturnAndNoEagerDataForm() {
        assertThat(render("",null)).contains("벡터 검색","VECTOR_1, VECTOR_2","&lt;img src=x&gt;","value=\"/vector-search\"","refresh=true","data-vector-list")
                .doesNotContain("<img src=x>","th:","data-vector-explorer");
    }
    @Test void detailOffersEmbeddingChoicesAndStartsDisabledUntilRealMetadataArrives() {
        assertThat(render("T<script>",null)).contains("data-vector-explorer","T&lt;script&gt;","fieldset disabled","DB 모델","OCI Generative AI","Cohere","OpenAI","data-consent","data-k","data-content","app-preview-dialog")
                .doesNotContain("T<script>","th:","value=\"TENANT");
    }
    @Test void errorDoesNotPretendEmptyOrShowSearchControls() {
        assertThat(render("T","ORA-00942 <table>")).contains("ORA-00942 &lt;table&gt;").doesNotContain("data-vector-explorer","조회된 테이블이 없습니다.");
    }
    @Test void publicModelPickerIsGenericAndNeedsNoCompartmentInFourLanguages() {
        for(var language:List.of("ko","en","zh-CN","ja")) {
            var html=render("ANY_TABLE",null,java.util.Locale.forLanguageTag(language));
            assertThat(html).contains("id=\"embedding-public-model\"", "data-public-model", "data-public-model-help",
                    "data-public-catalog-source", "data-public-catalog-date", "id=\"embedding-model\"",
                    "data-credential", "rel=\"noopener noreferrer\"",
                    "id=\"embedding-region-select\"", "data-region-source", "data-region-date", "data-region-help")
                    .doesNotContain("us-chicago-1", "TENANT", "ocid1.", "??vector.", "th:",
                            "data-oci-compartment", "data-oci-load", "data-oci-credential", "app-oci-catalog");
            assertThat(html).doesNotContain("data-oci-option open", "value=\"cohere.embed-v4.0\"");
        }
    }
    @Test void inputTypeHelpExplainsFourUsesInEveryLanguageWithoutSubmittingSearch() {
        var text=java.util.Map.of(
                "ko",List.of("input_type 도움말","검색하는 사람의 질문·검색어","검색 대상이 될 문서·예제를 저장할 때","분류에 사용할 벡터","비슷한 내용끼리 묶는 데 사용할 벡터"),
                "en",List.of("About input_type","The user's question or search query","Documents or examples stored for later retrieval","Embeddings for classification","Embeddings for grouping similar content"),
                "zh-CN",List.of("input_type 帮助","用户的问题或搜索词","存储将被检索的文档或示例","用于分类的向量","用于将相似内容分组的向量"),
                "ja",List.of("input_type の説明","検索する人の質問・検索語","検索対象となる文書・例を保存するとき","分類に使用するベクトル","似た内容をまとめるためのベクトル"));
        text.forEach((tag,descriptions)->{
            var html=render("ANY_TABLE",null,java.util.Locale.forLanguageTag(tag));
            assertThat(html).contains("type=\"button\" class=\"app-help-button\" popovertarget=\"embedding-input-type-help\"",
                    "id=\"embedding-input-type-help\" class=\"app-help-tooltip\" popover=\"auto\"",
                    "<dt>search_query</dt>","<dt>search_document</dt>","<dt>classification</dt>","<dt>clustering</dt>")
                    .doesNotContain("??vector.","th:");
            var visibleText=org.springframework.web.util.HtmlUtils.htmlUnescape(html);
            descriptions.forEach(description->assertThat(visibleText).contains(description));
        });
    }
    @Test void rowHistoryControlsAreTableSpecificAndTranslatedWithoutInitialMutation() {
        var titles=java.util.Map.of("ko","데이터 이력","en","Data history","zh-CN","数据历史","ja","データ履歴");
        titles.forEach((language,title)->{
            String html=render("T<script>",null,java.util.Locale.forLanguageTag(language));
            assertThat(html).contains(title,"data-row-history","data-table=\"T&lt;script&gt;\"","data-rh-toggle disabled",
                    "data-rh-action=\"prepare\" hidden disabled","data-rh-action=\"install\" hidden disabled","/vector-search/history","/js/table-row-history.mjs")
                    .doesNotContain("??rowHistory.","T<script>");
            assertThat(render("",null,java.util.Locale.forLanguageTag(language))).doesNotContain("data-row-history");
        });
    }
    @Test void fourLanguagePagesKeepDatabaseTextAndIdentifiersVerbatim() {
        var languages=java.util.Map.of("ko","벡터 검색","en","Vector search","zh-CN","向量搜索","ja","ベクトル検索");
        languages.forEach((tag,title)->{
            var locale=java.util.Locale.forLanguageTag(tag);
            var html=render("",null,locale);
            assertThat(html).contains("lang=\""+tag+"\"",title,"ANY_TABLE","스키마 日本語 原文","&lt;img src=x&gt;")
                    .doesNotContain("??ui.","th:","<img src=x>");
            assertThat(render("ANY_TABLE",null,locale)).contains(title,"name=\"language\"","data-consent","data-credential","data-public-model","data-region-select")
                    .doesNotContain("??ui.","??vector.","th:");
        });
    }
}
