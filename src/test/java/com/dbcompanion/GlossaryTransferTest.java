package com.dbcompanion;

import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.GlossaryTransfer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GlossaryTransferTest {
    private BusinessGlossary.Draft draft(String name,String definition){return new BusinessGlossary.Draft(name,List.of("alias"),definition,"COUNT(DISTINCT ID)",false);}
    private BusinessGlossary.Term term(String id,BusinessGlossary.Draft value){return new BusinessGlossary.Term(id,3,value.term(),value.aliases(),value.definition(),value.criteria(),value.enabled(),"now");}
    private GlossaryTransfer.Document document(List<BusinessGlossary.Draft> terms){return new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,1,terms);}
    @Test void jsonRoundTripPreservesContentWithoutDatabaseIdentity(){
        var json=JsonMapper.builder().build();var value=document(List.of(draft("Users","<script>Business definition</script>")));
        String encoded=json.writeValueAsString(value);
        assertThat(json.readValue(encoded,GlossaryTransfer.Document.class)).isEqualTo(value);
        assertThat(encoded).doesNotContain("revision","updatedAt","sourceOwner");
    }
    @Test void versionLimitsRequiredFieldsAndDuplicateNamesFailClosed(){
        assertThatThrownBy(()->new GlossaryTransfer.Document("other",1,List.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,2,List.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->document(List.of(draft("Users","one"),draft("users","two")))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->document(java.util.Collections.nCopies(501,draft("Users","one")))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->draft("Users","")).isInstanceOf(RuntimeException.class);
    }
    @Test void previewDistinguishesNewChangedIdenticalAndAmbiguousDestination(){
        var same=draft("Same","one");var old=draft("Update","old");var duplicate=draft("Duplicate","old");
        var preview=GlossaryTransfer.preview("APP",document(List.of(draft("New","new"),draft("Update","changed"),same,duplicate)),
                List.of(term("one",same),term("two",old),term("three",duplicate),term("four",duplicate)));
        assertThat(preview.entries()).extracting(GlossaryTransfer.Entry::status).containsExactly("NEW","UPDATE","UNCHANGED","CONFLICT");
        var selected=GlossaryTransfer.selected(preview,new GlossaryTransfer.Apply(preview.token(),List.of(0,1),true),"APP");
        assertThat(selected).hasSize(2);
        assertThat(selected.get(1).previous().id()).isEqualTo("two");
        for(var rows:List.of(List.of(2),List.of(3),List.of(-1),List.of(0,0),List.of(4)))
            assertThatThrownBy(()->GlossaryTransfer.selected(preview,new GlossaryTransfer.Apply(preview.token(),rows,true),"APP")).isInstanceOf(RuntimeException.class);
    }
    @Test void wrongOwnerTokenExpiredOrMissingConsentCannotWrite(){
        var preview=GlossaryTransfer.preview("APP",document(List.of(draft("New","new"))),List.of());
        assertThatThrownBy(()->GlossaryTransfer.selected(preview,new GlossaryTransfer.Apply(preview.token(),List.of(0),false),"APP")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->GlossaryTransfer.selected(preview,new GlossaryTransfer.Apply(preview.token(),List.of(0),true),"OTHER")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->GlossaryTransfer.selected(preview,new GlossaryTransfer.Apply("wrong",List.of(0),true),"APP")).isInstanceOf(RuntimeException.class);
        var expired=new GlossaryTransfer.Preview(preview.token(),"APP",Instant.EPOCH,preview.entries());
        assertThatThrownBy(()->GlossaryTransfer.selected(expired,new GlossaryTransfer.Apply(preview.token(),List.of(0),true),"APP")).isInstanceOf(RuntimeException.class);
    }
}
