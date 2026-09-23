package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.VectorSearch.*;
import com.dbcompanion.repository.VectorSearchRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.SqlParameterValue;
import static org.assertj.core.api.Assertions.*;

class VectorSearchTest {
    private final Selection selection=new Selection("APP","ANY_TABLE","SEMANTIC_VECTOR","EXAMPLE_TEXT");
    private final List<Column> columns=List.of(new Column("SEMANTIC_VECTOR","VECTOR",null),new Column("EXAMPLE_TEXT","CLOB",null),new Column("META","JSON",null));
    private Search search(String provider,String owner,String model,String credential,String region,String input,String metric,int k,boolean consent) {
        return new Search(selection,"한글 질문 ' ?",provider,owner,model,credential,region,input,metric,k,consent);
    }
    @Test void genericDiscoveryAndSelectionDoNotAssumeFeedbackColumnNames() {
        VectorSearch.columns(selection,columns);
        assertThatThrownBy(()->VectorSearch.columns(new Selection("APP","ANY_TABLE","EXAMPLE_TEXT",""),columns)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->VectorSearch.columns(new Selection("APP","ANY_TABLE","SEMANTIC_VECTOR","SEMANTIC_VECTOR"),columns)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->VectorSearch.scope("APP","OTHER")).isInstanceOf(Failure.class).hasMessageContaining("스키마");
    }
    @Test void exactSearchBindsVectorAndKAndQuotesCatalogIdentifiers() {
        var statement=VectorSearchRepository.rowsStatement(selection,columns,1,"[1,2,3]","COSINE",5);
        assertThat(statement.sql()).contains("\"APP\".\"ANY_TABLE\"","VECTOR_DISTANCE(t.\"SEMANTIC_VECTOR\", TO_VECTOR(?), COSINE)","FETCH EXACT FIRST ? ROWS ONLY","IS NOT NULL","DBC_DISTANCE, t.ROWID","DBMS_LOB.SUBSTR(t.\"EXAMPLE_TEXT\", 501, 1)")
                .doesNotContain("[1,2,3]","EMBEDDING VECTOR","FEEDBACK","CREATE","UPDATE");
        assertThat(((SqlParameterValue)statement.args().getFirst()).getValue()).isEqualTo("[1,2,3]");
        assertThat(statement.args().getLast()).isEqualTo(5);
    }
    @Test void browseUsesTenPlusOneAndHasNoEmbeddingCalls() {
        var statement=VectorSearchRepository.rowsStatement(selection,columns,3,null,null,0);
        assertThat(statement.sql()).contains("OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY").doesNotContain("VECTOR_DISTANCE","UTL_TO_EMBEDDING","VECTOR_EMBEDDING");
        assertThat(statement.args()).containsExactly(20);
        assertThatThrownBy(()->VectorSearchRepository.rowsStatement(selection,columns,0,null,null,0)).isInstanceOf(Failure.class);
    }
    @Test void tableAndColumnSqlMetacharactersStayQuoted() {
        var s=new Selection("O\"W","T\"; DROP TABLE X --","V","TEXT");
        var cols=List.of(new Column("V","VECTOR",null),new Column("TEXT","VARCHAR2",null));
        assertThat(VectorSearchRepository.rowsStatement(s,cols,1,null,null,0).sql()).contains("\"O\"\"W\".\"T\"\"; DROP TABLE X --\"");
        assertThatThrownBy(()->VectorSearchRepository.rowsStatement(selection,columns,1,"[1]","COSINE); DELETE",10)).isInstanceOf(Failure.class);
    }
    @Test void rowDetailsPreserveClobsAndJsonWithoutArbitraryObjectConversion() {
        var cols=List.of(new Column("V","VECTOR",null),new Column("DOC","CLOB",null),new Column("JSON_DATA","JSON",null),new Column("FILE","BFILE",null));
        var statement=VectorSearchRepository.detailStatement(new Selection("APP","T","V","DOC"),cols,"AAABBBCCC000001abc");
        assertThat(statement.sql()).contains("VECTOR_SERIALIZE(t.\"V\" RETURNING CLOB)","t.\"DOC\"","JSON_SERIALIZE(t.\"JSON_DATA\" RETURNING CLOB)","CAST(NULL AS VARCHAR2(1))","CHARTOROWID(?)").doesNotContain("SUBSTR","t.\"FILE\"");
        assertThat(statement.args()).containsExactly("AAABBBCCC000001abc");
        assertThatThrownBy(()->VectorSearch.rowId("' OR 1=1 --")).isInstanceOf(Failure.class);
    }
    @Test void localEmbeddingBindsInputAndNeverCallsExternalAdapter() {
        var request=search("database","ML_OWNER","MY_MODEL","","","","COSINE",10,false);
        var statement=VectorSearchRepository.embeddingStatement(request,"APP",null);
        assertThat(statement.sql()).contains("VECTOR_EMBEDDING(\"ML_OWNER\".\"MY_MODEL\" USING ? AS DATA)").doesNotContain(request.text(),"UTL_TO_EMBEDDING");
        assertThat(statement.args()).containsExactly(request.text());
    }
    @Test void remoteParamsUseOnlyOfficialEndpointsAndLoginOwnedCredentials() {
        var oci=search("ocigenai","","cohere.embed-multilingual-v3.0","MY_CRED","us-chicago-1","search_query","COSINE",10,true);
        assertThat(oci.externalParams()).containsEntry("url","https://inference.generativeai.us-chicago-1.oci.oraclecloud.com/20231130/actions/embedText")
                .containsEntry("credential_name","MY_CRED").containsEntry("inputType","SEARCH_QUERY").doesNotContainKeys("input_type","private_key","password");
        assertThat(search("openai","","text-embedding-3-small","C","","","COSINE",1,true).endpoint()).isEqualTo("https://api.openai.com/v1/embeddings");
        assertThat(search("cohere","","embed-multilingual-v3.0","C","","search_query","COSINE",1,true).endpoint()).isEqualTo("https://api.cohere.ai/v1/embed");
        assertThatThrownBy(()->search("ocigenai","","M","C","evil.com/path","","COSINE",10,true)).isInstanceOf(Failure.class);
    }
    @Test void ociInputTypesUseRestFieldAndUppercaseValuesWithoutCohereAlias() {
        var types=java.util.Map.of("search_query","SEARCH_QUERY", "search_document","SEARCH_DOCUMENT",
                "classification","CLASSIFICATION", "clustering","CLUSTERING");
        types.forEach((input,wireValue)-> {
            var request=search("ocigenai","","cohere.embed-v4.0","C","us-chicago-1",input,"COSINE",3,true);
            assertThat(request.inputType()).isEqualTo(input);
            assertThat(request.externalParams()).containsEntry("inputType",wireValue).doesNotContainKey("input_type");
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var json=mapper.readTree(mapper.writeValueAsString(request.externalParams()));
            assertThat(json.get("inputType").asString()).isEqualTo(wireValue);
            assertThat(json.has("input_type")).isFalse();
        });
    }
    @Test void cohereInputTypesKeepSnakeCaseAndLowercaseValues() {
        for(var input:List.of("search_query","search_document","classification","clustering")) {
            assertThat(search("cohere","","embed-v4.0","C","",input,"COSINE",3,true).externalParams())
                    .containsEntry("input_type",input).doesNotContainKey("inputType");
        }
    }
    @Test void providerDefaultOmitsBothInputTypeFields() {
        for(var provider:List.of("ocigenai","cohere","openai")) {
            assertThat(search(provider,"","M","C","us-chicago-1","","COSINE",3,true).externalParams())
                    .doesNotContainKeys("inputType","input_type");
        }
    }
    @Test void apiCredentialNameIsUnqualifiedAndExternalSchemaStaysLoginOwned() {
        var request=search("ocigenai","","cohere.embed-v4.0","DEMO_APP_QA_VECTOR_CRED","us-chicago-1","search_query","COSINE",3,true);
        // Selected table is APP.ANY_TABLE; the credential must still belong to the login.
        var params=request.externalParams();
        assertThat(request.executionSchema("DEMO_APP")).isEqualTo("DEMO_APP");
        assertThat(request.selection().schema()).isEqualTo("APP");
        assertThat(params).containsEntry("credential_name","DEMO_APP_QA_VECTOR_CRED");
        var json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(params);
        var statement=VectorSearchRepository.embeddingStatement(request,"DEMO_APP",json);
        assertThat(statement.sql()).contains("UTL_TO_EMBEDDING(?, JSON(?))").doesNotContain("DEMO_APP","DEMO_APP_QA_VECTOR_CRED");
        assertThat(statement.args().getLast()).isEqualTo(json);
        assertThat(params).doesNotContainKeys("private_key","password","compartment_ocid");
    }
    @Test void unusualCredentialNamesKeepTheirExistingCaseAndIdentifierBoundaries() {
        assertThat(search("cohere","","M","MiXeD","","","COSINE",1,true).externalParams())
                .containsEntry("credential_name","\"MiXeD\"");
        assertThat(search("cohere","","M","C\".OTHER","","","COSINE",1,true).externalParams())
                .containsEntry("credential_name","\"C\"\".OTHER\"");
        assertThat(search("cohere","","M","C_$#9","","","COSINE",1,true).externalParams())
                .containsEntry("credential_name","C_$#9");
    }
    @Test void onlyExternalEmbeddingChangesExecutionSchemaAndTablesRemainQualified() {
        var remote=search("cohere","","M","C","","","COSINE",1,true);
        assertThat(remote.executionSchema("LOGIN")).isEqualTo("LOGIN");
        assertThatThrownBy(()->remote.executionSchema("")).isInstanceOf(Failure.class);
        var local=search("database","ML_OWNER","MODEL","","","","COSINE",1,false);
        assertThat(local.executionSchema("LOGIN")).isEqualTo("APP");
        assertThat(VectorSearchRepository.rowsStatement(remote.selection(),columns,1,"[1,2,3]","COSINE",1).sql())
                .contains("FROM \"APP\".\"ANY_TABLE\" t").doesNotContain("FROM \"LOGIN\"");
    }
    @Test void remoteInvocationRequiresConsentAndValidProviderOptions() {
        assertThatThrownBy(()->search("openai","","M","C","","","COSINE",10,false)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->search("openai","","M","C","","search_query","COSINE",10,true)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->search("arbitrary","","M","C","","","COSINE",10,true)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->search("database","APP","MODEL","","","","COSINE",101,false)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->search("database","APP","MODEL","","","",null,10,false)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->new Search(selection,"가".repeat(1334),"database","APP","MODEL","","","","COSINE",10,false)).isInstanceOf(Failure.class);
    }
    @Test void cacheIsLoginAndSchemaScopedAndRefreshIsExplicit() {
        var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));var calls=new AtomicInteger();
        java.util.function.Supplier<List<Table>> loader=()->{calls.incrementAndGet();return List.of(new Table("T",null,List.of("V")));};
        state.vectorTables("APP",false,loader);state.vectorTables("APP",false,loader);assertThat(calls).hasValue(1);
        state.vectorTables("OTHER",false,loader);state.vectorTables("APP",true,loader);assertThat(calls).hasValue(3);
        var second=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));second.vectorTables("APP",false,loader);assertThat(calls).hasValue(4);
        var options=new Options(List.of(),List.of(),null,null);state.vectorOptions(false,()->options);
        assertThat(state.vectorOptions(false,()->{throw new AssertionError("Unexpected repeated query");})).isSameAs(options);
    }
    @Test void unicodePreviewDoesNotSplitSurrogatePairs() {
        assertThat(VectorSearch.clip("가😀나",2)).isEqualTo("가");
        assertThat(VectorSearch.clip(null,500)).isNull();
    }
}
