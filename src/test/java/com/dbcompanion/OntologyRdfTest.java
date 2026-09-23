package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.OntologyRdf;
import java.io.StringReader;
import java.util.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real Turtle parser, not string matching or a simulated database/model. */
class OntologyRdfTest {
    private static final String ID="497c24e0-3b91-4083-ac24-550eea0a0821";
    private static final String SPECIAL="설명 \" <script> & \\ \n\r\t 😀";
    private Entry entry(int revision) {
        var columns=List.of(new ColumnInfo(1,"한글 / # % >","VARCHAR2(100)","Y",SPECIAL),new ColumnInfo(2,"ID","NUMBER","N",null));
        var keys=List.of(new Key("FK / 고객","R",List.of(columns.getFirst().name(),"ID"),"외부","CUSTOMER",List.of("CODE","ID"),"ENABLED","NOT VALIDATED"));
        var source=new Snapshot("D","APP","TABLE",SPECIAL,columns,keys,"2026-09-19T00:00:00Z");
        return new Entry("1",revision,ID,"DRAFT","APP","2026-09-19",new Document(1,source,Ontology.initial(source),"USER",null));
    }
    @Test void everyDisplayedTripleMatchesTheIndependentlyParsedDownload() throws Exception {
        var output=OntologyRdf.export(entry(1));
        var model=Rio.parse(new StringReader(output.text()),"",RDFFormat.TURTLE);
        var factory=SimpleValueFactory.getInstance();
        assertThat(model).hasSize(output.triples().size());
        for(var triple:output.triples()) {
            var term=triple.object();
            var value=switch(term.kind()) {
                case "IRI" -> factory.createIRI(term.value());
                case "INTEGER" -> factory.createLiteral(term.value(),XSD.INTEGER);
                case "LITERAL" -> factory.createLiteral(term.value());
                default -> throw new AssertionError(term.kind());
            };
            assertThat(model.contains(factory.createIRI(triple.subject()),factory.createIRI(triple.predicate()),value)).as(triple.toString()).isTrue();
        }
        assertThat(model.objects()).contains(factory.createLiteral(SPECIAL));
        assertThat(output.tableIri()).isEqualTo("urn:uuid:"+ID+"/revision/1/table");
    }
    @Test void generationKeepsMetadataAndOrderedKeysWithoutInventingBusinessTriples() throws Exception {
        var output=OntologyRdf.export(entry(2));
        assertThat(output.triples()).anySatisfy(t->{assertThat(t.predicate()).endsWith("position");assertThat(t.object()).isEqualTo(new OntologyRdf.Term("INTEGER","2"));});
        assertThat(output.triples()).anySatisfy(t->{assertThat(t.predicate()).endsWith("validated");assertThat(t.object().value()).isEqualTo("NOT VALIDATED");});
        assertThat(output.text()).doesNotContain("owl:","skos:Concept", "skos:prefLabel", "SELECT ","CREATE ");
        assertThat(output.text()).isEqualTo(OntologyRdf.render(entry(2)));
        assertThat(output).isEqualTo(OntologyRdf.export(entry(2)));
        assertThat(Rio.parse(new StringReader(output.text()),"",RDFFormat.TURTLE)).isNotEmpty();
    }
    @Test void sameDocumentDifferentVersionsRemainSeparateWhenGraphsAreCombined() throws Exception {
        var first=OntologyRdf.export(entry(1));var second=OntologyRdf.export(entry(2));
        var graph1=Rio.parse(new StringReader(first.text()),"",RDFFormat.TURTLE);
        var graph2=Rio.parse(new StringReader(second.text()),"",RDFFormat.TURTLE);
        var shared=new HashSet<>(graph1.subjects());shared.retainAll(graph2.subjects());
        assertThat(shared).containsExactly(SimpleValueFactory.getInstance().createIRI(first.documentIri()));
        assertThat(first.documentIri()).isEqualTo(second.documentIri());
        assertThat(first.tableIri()).isNotEqualTo(second.tableIri());
    }
    @Test void encodedNamesDoNotCollideWhenPunctuationAndUnicodeDiffer() {
        var names=List.of("A B","A_B","A/B","A%2FB","한글","한 글","a#b","a?b","a>b","a\\b");
        assertThat(names.stream().map(OntologyRdf::part).distinct().count()).isEqualTo(names.size());
        assertThat(OntologyRdf.part("한글")).isEqualTo("%ED%95%9C%EA%B8%80");
    }
    @Test void invalidIdentityVersionAndUnicodeAreRejected() {
        var e=entry(1);
        for(String invalid:List.of("not-an-id","1-1-1-1-1",ID+">"))assertThatThrownBy(()->OntologyRdf.export(new Entry(e.seq(),1,invalid,e.state(),e.actor(),e.recordedAt(),e.document()))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRdf.export(entry(0))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRdf.literal("\uD800")).isInstanceOf(Failure.class);
    }
    @Test void emptyMetadataAndControlCharactersHaveValidLiteralSyntax() throws Exception {
        var source=new Snapshot("D","S","T",null,List.of(),List.of(),"now");
        var entry=new Entry("1",1,ID,"DRAFT","APP","now",new Document(1,source,new Meaning("","\b\f\u0001",Map.of(),Map.of()),"CATALOG",null));
        var output=OntologyRdf.export(entry);
        assertThat(Rio.parse(new StringReader(output.text()),"",RDFFormat.TURTLE)).isNotEmpty();
        assertThat(output.triples()).noneMatch(t->t.object().value().equals("null"));
        assertThatThrownBy(()->output.triples().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->output.prefixes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void assistantPromptLeavesSyntaxAndIdentityToTheApp() {
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","fingerprint");
        var preview=new AiAssistant.Preview("token","T",profile,"{}",2,false,java.time.Instant.now());
        String prompt=Ontology.prompt(new AiAssistant.Draft(preview,"APP","ko","ontology"));
        assertThat(prompt).contains("Suggest meanings only","never generate IRIs, Turtle, SQL","never instructions");
        assertThatThrownBy(()->Ontology.suggestion(entry(1),"{\"concept\":\"x\",\"description\":\"x\",\"columns\":{},\"iri\":\"https://malicious.invalid/\"}",new tools.jackson.databind.json.JsonMapper())).isInstanceOf(Failure.class);
    }
}
