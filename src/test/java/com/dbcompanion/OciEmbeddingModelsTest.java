package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.OciEmbeddingModels.*;
import com.dbcompanion.repository.OciEmbeddingModelRepository;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Pure contracts; no mocked Oracle connection or fabricated OCI service. */
class OciEmbeddingModelsTest {
    final Query query=new Query("CATALOG_CRED","us-chicago-1","ocid1.compartment.oc1..test");
    DatabaseSession session(){return new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));}
    @Test void catalogueInputNeverAcceptsUrlsOrInferenceText(){
        assertThatThrownBy(()->new Query("C","example.com/path",query.compartment())).isInstanceOf(VectorSearch.Failure.class);
        assertThatThrownBy(()->new Query("C","us-chicago-1","https://example.invalid")).isInstanceOf(VectorSearch.Failure.class);
        assertThatThrownBy(()->new Query("C","us-chicago-1","ocid1.compartment.oc1..x?evil=1")).isInstanceOf(VectorSearch.Failure.class);
        assertThatThrownBy(()->new Request(query,"token",true)).isInstanceOf(VectorSearch.Failure.class);
        assertThatThrownBy(()->new Request(query,"x\n",false)).isInstanceOf(VectorSearch.Failure.class);
        assertThat(new Query("C","ap-osaka-1","ocid1.tenancy.oc1..abc").region()).isEqualTo("ap-osaka-1");
    }
    @Test void sdkUsesBoundArgumentsAndOnlyListModels(){
        String sql=OciEmbeddingModelRepository.sql("\"SYS\".\"SDK\"","\"SYS\".\"RESPONSE\"","\"SYS\".\"CAPS\"");
        assertThat(sql).contains("LIST_MODELS(compartment_id=>?,region=>?,credential_name=>?,page=>?",
                "'TEXT_EMBEDDINGS'","lifecycle_state=>'ACTIVE',limit=>100","opc-next-page","? := result_json.to_clob()")
                .doesNotContain("UTL_TO_EMBEDDING","CREATE_MODEL","SEND_REQUEST","GRANT","CREATE_CREDENTIAL",query.compartment());
        assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(5);
    }
    @Test void responseSelectsActiveBaseEmbeddingsAndPreservesNames(){
        var result=OciEmbeddingModels.parse("""
                {"items":[
                  {"name":"cohere.embed-v4.0","id":"m1","vendor":"Cohere","version":"4","state":"ACTIVE","type":"BASE","capabilities":["TEXT_EMBEDDINGS"],"deprecatedAt":null},
                  {"name":"chat.model","state":"ACTIVE","type":"BASE","capabilities":["TEXT_GENERATION"]},
                  {"name":"private.model","state":"ACTIVE","type":"CUSTOM","capabilities":["TEXT_EMBEDDINGS"]},
                  {"name":"old.model","state":"DELETED","type":"BASE","capabilities":["TEXT_EMBEDDINGS"]}
                ],"nextPage":"next+/="}
                """,new JsonMapper());
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().name()).isEqualTo("cohere.embed-v4.0");
        assertThat(result.nextPage()).isEqualTo("next+/=");
        assertThat(result.items().getFirst().deprecatedAt()).isEmpty();
        assertThat(OciEmbeddingModels.parse("{\"items\":[]}",new JsonMapper()).items()).isEmpty();
        assertThatThrownBy(()->OciEmbeddingModels.parse("{}",new JsonMapper())).isInstanceOf(VectorSearch.Failure.class);
    }
    @Test void cachedPagesAreScopedByLoginCredentialRegionAndCompartment(){
        var state=session();var calls=new AtomicInteger();
        java.util.function.Supplier<Page> loader=()->{calls.incrementAndGet();return new Page(List.of(),"p2");};
        var first=new Request(query,"",false);
        assertThat(state.ociModels(first,loader).cached()).isFalse();
        state.selectSchema("OTHER");assertThat(state.ociModels(first,loader).cached()).isTrue();
        assertThat(calls).hasValue(1);
        state.ociModels(new Request(new Query("OTHER",query.region(),query.compartment()),"",false),loader);
        state.ociModels(new Request(new Query(query.credential(),"ap-osaka-1",query.compartment()),"",false),loader);
        state.ociModels(new Request(new Query(query.credential(),query.region(),"ocid1.compartment.oc1..other"),"",false),loader);
        session().ociModels(first,loader);assertThat(calls).hasValue(5);
    }
    @Test void continuationMustHaveBeenReturnedAndRefreshInvalidatesAllPages(){
        var state=session();var second=new Request(query,"p2",false);
        assertThatThrownBy(()->state.ociModels(second,()->{throw new AssertionError("Must not call OCI");})).isInstanceOf(VectorSearch.Failure.class);
        state.ociModels(new Request(query,"",false),()->new Page(List.of(),"p2"));
        state.ociModels(second,()->new Page(List.of(),""));
        state.ociModels(new Request(query,"",true),()->new Page(List.of(),""));
        assertThatThrownBy(()->state.ociModels(second,()->{throw new AssertionError("Stale continuation");})).isInstanceOf(VectorSearch.Failure.class);
    }
    @Test void failedRefreshDoesNotPresentOldDataAsFreshAndCacheIsBounded(){
        var state=session();state.ociModels(new Request(query,"",false),()->new Page(List.of(),""));
        assertThatThrownBy(()->state.ociModels(new Request(query,"",true),()->{throw new IllegalStateException("OCI failure");})).hasMessage("OCI failure");
        assertThat(state.ociModels(new Request(query,"",false),()->new Page(List.of(),"")).cached()).isFalse();
        for(int i=0;i<32;i++)state.ociModels(new Request(new Query("C"+i,query.region(),query.compartment()),"",false),()->new Page(List.of(),""));
        assertThat(state.ociModels(new Request(query,"",false),()->new Page(List.of(),"")).cached()).isFalse();
    }
}
