package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class OntologyScopeRepositoryTest {
    final JsonMapper json=new JsonMapper();final OntologyRelationsTest fixture=new OntologyRelationsTest();
    List<String> requested;String sql;int preparations;boolean missing,badPayload;List<Object> values;
    PreparedStatement statement(String text){
        preparations++;sql=text;var args=new TreeMap<Integer,Object>();
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setString","setObject"->{args.put((int)a[0],a[1]);yield null;}
            case "executeQuery"->{values=new ArrayList<>(args.values());var names=args.tailMap(2).values().stream().map(Object::toString).toList();yield result(missing?List.of():names);}
            default->empty(m.getReturnType());
        });
    }
    ResultSet result(List<String> names){
        int[] pos={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next"->++pos[0]<names.size();case "getInt"->1;
            case "getString"->switch((int)a[0]){case 1->"1";case 3->UUID.nameUUIDFromBytes(names.get(pos[0]).getBytes()).toString();case 4->"DRAFT";case 5->"APP";case 6->"now";case 8->names.get(pos[0]);default->throw new AssertionError();};
            case "getClob"->{var e=fixture.entry(badPayload?"OTHER":names.get(pos[0]),List.of(fixture.col("ID","NUMBER")),List.of(),Map.of());yield new SerialClob(json.writeValueAsString(e.document()).toCharArray());}
            default->empty(m.getReturnType());
        });
    }
    final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->m.getName().equals("prepareStatement")?statement((String)a[0]):empty(m.getReturnType()));}
        @Override public Connection getConnection(String u,String p){throw new AssertionError();}
    };
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final OntologyRepository repository=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public void require(String schema,String login){}
    };
    @Test void selectedNamesAreBoundBeforeReadingClobs(){
        var rows=repository.relationshipEntries("APP","APP",List.of("Z'--","A"));
        assertThat(rows).extracting(e->e.document().source().table()).containsExactly("A","Z'--");
        assertThat(sql).contains("WHERE OBJECT_OWNER=? AND OBJECT_NAME IN (?,?)").doesNotContain("Z'--");assertThat(values).containsExactly("APP","A","Z'--");
    }
    @Test void emptyOrMissingSelectionNeverReadsAllClobs(){
        assertThatThrownBy(()->repository.relationshipEntries("APP","APP",List.of())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->repository.relationshipEntries("APP","APP",null)).isInstanceOf(Failure.class);assertThat(preparations).isZero();
    }
    @Test void missingSelectedTableOrMismatchedPayloadFailsWholeSelection(){
        missing=true;assertThatThrownBy(()->repository.relationshipEntries("APP","APP",List.of("A"))).isInstanceOf(Failure.class);
        missing=false;badPayload=true;assertThatThrownBy(()->repository.relationshipEntries("APP","APP",List.of("A"))).isInstanceOf(Failure.class);
    }
}
