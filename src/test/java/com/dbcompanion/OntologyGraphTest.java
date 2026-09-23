package com.dbcompanion;

import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.Ontology;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OntologyGraphTest {
    GraphData empty(){return new GraphData("APP",List.of(),"now");}
    @Test void graphCacheIsSchemaScopedAndExplicitRefreshAndWritesInvalidate(){
        var state=new State();var calls=new AtomicInteger();
        java.util.function.Supplier<GraphData> load=()->{calls.incrementAndGet();return empty();};
        state.graph("APP",false,load);state.graph("APP",false,load);assertThat(calls).hasValue(1);
        state.graph("OTHER",false,load);assertThat(calls).hasValue(2);
        state.catalog("APP",true,()->new Catalog("READY",false,List.of(),List.of(),"now"));
        state.graph("APP",false,load);assertThat(calls).hasValue(3);
        state.clear("APP");state.graph("APP",false,load);assertThat(calls).hasValue(4);
        assertThatThrownBy(()->state.graph("APP",true,()->{throw new IllegalStateException("read failed");})).isInstanceOf(IllegalStateException.class);
        state.graph("APP",false,load);assertThat(calls).hasValue(5);
    }
    @Test void graphLimitsAreExplicitAndNeverSilentlyTruncate(){
        var table=new GraphTable("T",1,"uuid","DRAFT","now","now",List.of());
        assertThatThrownBy(()->new GraphData("APP",Collections.nCopies(501,table),"now")).isInstanceOf(Failure.class);
        var fk=new Key("K","R",List.of("A","B"),"APP","P",List.of("C","D"),"DISABLED","NOT VALIDATED");
        var many=new GraphTable("T",1,"uuid","DRAFT","now","now",Collections.nCopies(10001,fk));
        assertThatThrownBy(()->new GraphData("APP",List.of(many),"now")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->empty().tables().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void graphRepositoryReadsLatestProjectedKeysNotBusinessRowsOrAllColumns() throws Exception {
        var code=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/OntologyRepository.java"));
        var method=code.substring(code.indexOf("public GraphData graph("),code.indexOf("public Entry entry("));
        assertThat(method).contains("JSON_QUERY(PAYLOAD,'$.source.keys' RETURNING CLOB ERROR ON ERROR)",
                "WHERE OBJECT_OWNER=?", "WHERE RN=1", "FETCH FIRST 501 ROWS ONLY", "clob.free()");
        assertThat(method).doesNotContain("metadata.columns", "metadata.table", "structure.constraints", "INSERT INTO", "UPDATE ", "CREATE ");
    }
    @Test void shippedRendererAndLicenseAreLocalResources() throws Exception {
        try(var script=getClass().getResourceAsStream("/META-INF/resources/webjars/cytoscape/3.34.1/dist/cytoscape.min.js");
            var license=getClass().getResourceAsStream("/META-INF/resources/webjars/cytoscape/3.34.1/LICENSE")){
            assertThat(script).isNotNull();assertThat(license).isNotNull();
            assertThat(new String(script.readNBytes(2000),java.nio.charset.StandardCharsets.UTF_8)).containsIgnoringCase("cytoscape");
            assertThat(new String(license.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("Permission is hereby granted");
        }
    }
    @Test void schemaMismatchFailsBeforeConnectionAndSessionCacheHitNeedsNoDatabase(){
        var ds=new com.dbcompanion.common.db.SessionDataSource();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        var json=new tools.jackson.databind.json.JsonMapper();
        var repository=new com.dbcompanion.repository.OntologyRepository(jdbc,json,new com.dbcompanion.repository.DatabaseRepository(jdbc),new com.dbcompanion.repository.TableStructureRepository(jdbc));
        var service=new com.dbcompanion.service.OntologyService(ds,repository,new com.dbcompanion.repository.AiAssistantRepository(jdbc),null,json);
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var session=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            session.initialize(new com.dbcompanion.model.DatabaseSession(new com.dbcompanion.model.DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
            var graph=empty();session.metadata().ontology().graph("APP",false,()->graph);
            assertThat(service.graph(session,"APP",false)).isSameAs(graph);
            assertThatThrownBy(()->service.graph(session,"OTHER",false)).isInstanceOf(Failure.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
}
