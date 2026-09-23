package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import java.io.StringReader;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologySkosTest {
    static final String ID="497c24e0-3b91-4083-ac24-550eea0a0821";
    static final ValueFactory VF=SimpleValueFactory.getInstance();
    Entry entry(int revision,String label,List<String> aliases){
        var source=new Snapshot("DB","APP","SALES",null,List.of(new ColumnInfo(1,"AMOUNT","NUMBER","N","amount"),new ColumnInfo(2,"EMAIL","VARCHAR2(100)","Y",null)),List.of(),"now");
        var meaning=Ontology.initial(source);var columns=new LinkedHashMap<>(meaning.columns());
        var definition=new OntologyWizard.Definition(label,aliases,"","","MATCH","saved reason","review required","APP.P","now",10);
        columns.put("AMOUNT",new ColumnMeaning("설명 \" <script> & \\ \n 😀","UNKNOWN",definition));
        columns.put("EMAIL",new ColumnMeaning("private","UNKNOWN",new OntologyWizard.Definition("private label",List.of("private alias"),"","","MATCH","","","APP.P","now",10)));
        return new Entry("1",revision,ID,"DRAFT","APP","now",new Document(1,source,new Meaning("매출","sales definition",columns,Map.of()),"AI_REVIEWED","APP.P"));
    }
    Model parse(String value) throws Exception {return Rio.parse(new StringReader(value),"",RDFFormat.TURTLE);}
    IRI iri(String value){return VF.createIRI(value);}
    @Test void skosLabelsAreSeparateConceptsWithVersionStateAndSourceLinks() throws Exception {
        var e=entry(2,"매출액",List.of("판매금액","Revenue"));var output=OntologyRdf.export(e);var rdf=parse(output.text());
        String resource=output.tableIri()+"/column/AMOUNT",concept=resource+"/concept";
        assertThat(rdf.contains(iri(resource),iri("urn:dbcompanion:ontology:concept"),iri(concept))).isTrue();
        assertThat(rdf.contains(iri(concept),RDF.TYPE,SKOS.CONCEPT)).isTrue();
        assertThat(rdf.contains(iri(resource),RDF.TYPE,SKOS.CONCEPT)).isFalse();
        assertThat(rdf.contains(iri(concept),SKOS.PREF_LABEL,VF.createLiteral("매출액"))).isTrue();
        assertThat(rdf.contains(iri(concept),SKOS.ALT_LABEL,VF.createLiteral("판매금액"))).isTrue();
        assertThat(rdf.contains(iri(concept),SKOS.IN_SCHEME,iri(output.versionIri()+"/vocabulary"))).isTrue();
        assertThat(rdf.contains(iri(concept),iri("urn:dbcompanion:ontology:state"),VF.createLiteral("DRAFT"))).isTrue();
        assertThat(rdf.contains(iri(resource),RDFS.LABEL,VF.createLiteral("매출액"))).isTrue();
        assertThat(rdf.contains(iri(resource),iri("urn:dbcompanion:ontology:alias"),VF.createLiteral("판매금액"))).isTrue();
        assertThat(rdf).hasSize(output.triples().size());
        assertThat(output.text()).doesNotContain("owl:","skos:exactMatch","skos:broader");
        assertThat(e.document().meaning().columns().get("AMOUNT").definition().aliases()).containsExactly("판매금액","Revenue");
        var mapper=new JsonMapper();assertThat(Ontology.parse(Ontology.json(e.document(),mapper),mapper)).isEqualTo(e.document());
    }
    @Test void derivedLabelsNormalizeWhitespaceUnicodeAndExcludeDuplicatesWithoutEditingSource() throws Exception {
        var e=entry(1," café ",List.of("cafe\u0301","판매금액"," 판매금액 "," "));
        var output=OntologyRdf.export(e);var rdf=parse(output.text());var concept=iri(output.tableIri()+"/column/AMOUNT/concept");
        assertThat(rdf.filter(concept,SKOS.PREF_LABEL,null).objects()).containsExactly(VF.createLiteral("café"));
        assertThat(rdf.filter(concept,SKOS.ALT_LABEL,null).objects()).containsExactly(VF.createLiteral("판매금액"));
        assertThat(e.document().meaning().columns().get("AMOUNT").definition().label()).isEqualTo(" café ");
        assertThat(e.document().meaning().columns().get("AMOUNT").definition().aliases()).hasSize(4);
    }
    @Test void noPreferredNameMeansNoColumnConceptAndVersionsDoNotMerge() throws Exception {
        var empty=OntologyRdf.export(entry(1,"  ",List.of("unattached alias")));
        assertThat(parse(empty.text()).contains(iri(empty.tableIri()+"/column/AMOUNT"),iri("urn:dbcompanion:ontology:concept"),null)).isFalse();
        var first=OntologyRdf.export(entry(1,"매출액",List.of("판매금액")));var second=OntologyRdf.export(entry(2,"매출액",List.of("판매금액")));
        var combined=parse(first.text()+second.text());
        assertThat(combined.filter(null,RDF.TYPE,SKOS.CONCEPT).subjects()).hasSize(6);
        assertThat(combined.filter(null,RDF.TYPE,SKOS.CONCEPT_SCHEME).subjects()).hasSize(2);
    }
    @Test void actualContextContainsSkosButExcludesSensitiveColumnLabels() throws Exception {
        var e=entry(1,"매출액",List.of("판매금액"));var json=new JsonMapper();var bundle=OntologyContext.bundle(e,List.of(),json);
        String rdf=json.readTree(bundle.payload()).path("graphs").get(0).path("rdf").asString();
        assertThat(rdf).contains("skos:prefLabel","skos:altLabel","판매금액").doesNotContain("private label","private alias","EMAIL");
        assertThat(parse(rdf)).isNotEmpty();
        assertThat(parse(OntologyContext.compact(OntologyRdf.export(e)))).isNotEmpty();
    }
}
