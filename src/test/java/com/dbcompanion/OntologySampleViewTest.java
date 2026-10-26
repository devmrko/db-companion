package com.dbcompanion;

import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.ColumnInfo;
import com.dbcompanion.repository.OntologyWizardRepository;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class OntologySampleViewTest {
    String definition="SELECT CODE FROM DATA.T WITH READ ONLY",dependencyType="TABLE",link=null;
    boolean external,virtual,ordinary=true;int reads,limit;
    final List<Integer> timeouts=new ArrayList<>();
    ResultSet result(List<List<String>> rows){
        int[] index={-1};var meta=proxy(ResultSetMetaData.class,(p,m,a)->m.getName().equals("getColumnCount")?1:empty(m.getReturnType()));
        return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next" -> ++index[0]<rows.size();case "getMetaData" -> meta;
            case "getString" -> rows.get(index[0]).get((int)a[0]-1);
            case "getInt" -> Integer.parseInt(rows.get(index[0]).get((int)a[0]-1));
            case "getLong" -> Long.parseLong(rows.get(index[0]).get((int)a[0]-1));
            default -> empty(m.getReturnType());
        });
    }
    PreparedStatement statement(String sql){var args=new HashMap<Integer,Object>();return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
        case "setString","setObject","setInt" -> {args.put((int)a[0],a[1]);yield null;}
        case "setQueryTimeout" -> {timeouts.add((int)a[0]);yield null;}
        case "executeQuery" -> {
            if(sql.contains("SYS.ALL_OBJECTS"))yield result(List.of(List.of("V".equals(args.get(2))?"VIEW":"TABLE")));
            if(sql.contains("SYS.ALL_VIEWS"))yield result(List.of(List.of(String.valueOf(definition.length()),definition)));
            if(sql.contains("SYS.ALL_DEPENDENCIES"))yield result(List.of(Arrays.asList("DATA","T",dependencyType,link)));
            if(sql.contains("SYS.ALL_EXTERNAL_TABLES"))yield result(List.of(List.of(external?"1":"0")));
            if(sql.contains("VIRTUAL_COLUMN='YES'"))yield result(List.of(List.of(virtual?"1":"0")));
            if(sql.contains("SYS.ALL_TABLES"))yield result(List.of(List.of(ordinary?"1":"0")));
            if(sql.contains("SYS.ALL_TAB_COLS"))yield result(List.of(List.of("CODE","VARCHAR2")));
            assertThat(sql).contains("FROM \"APP\".\"V\" WHERE ROWNUM <= ?");reads++;limit=(int)args.get(1);yield result(List.of(List.of("KR")));
        }
        default -> empty(m.getReturnType());
    });}
    final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->m.getName().equals("prepareStatement")?statement((String)a[0]):empty(m.getReturnType()));}
        @Override public Connection getConnection(String u,String p){throw new AssertionError();}
    };
    final OntologyWizardRepository repository=new OntologyWizardRepository(new JdbcTemplate(source));
    final List<ColumnInfo> columns=List.of(new ColumnInfo(1,"CODE","VARCHAR2(20)","Y",""));
    final Snapshot snapshot=new Snapshot("DB","APP","V","",columns,List.of(),"now");
    @Test void projectionViewSupportsBothBoundedPathsWithoutQueryingBaseDirectly(){
        assertThat(repository.sample(snapshot,columns,20)).containsExactly(List.of("KR"));assertThat(limit).isEqualTo(20);
        assertThat(repository.profileRows(snapshot,columns)).containsExactly(List.of("KR"));assertThat(limit).isEqualTo(1000);assertThat(reads).isEqualTo(2);assertThat(timeouts).allMatch(t->t<=10);
    }
    @Test void linksFunctionsAndUnverifiableDependenciesFailBeforeReadingRows(){
        for(String sql:List.of("SELECT CODE FROM DATA.T@REMOTE","SELECT f(CODE) FROM DATA.T","SELECT CODE FROM DATA.T WHERE f(CODE)=1","SELECT CODE FROM APP.V")){
            definition=sql;assertThatThrownBy(()->repository.profileRows(snapshot,columns)).isInstanceOf(Failure.class);
        }
        definition="SELECT CODE FROM DATA.T";link="REMOTE";assertThatThrownBy(()->repository.profileRows(snapshot,columns)).isInstanceOf(Failure.class);
        link=null;dependencyType="FUNCTION";assertThatThrownBy(()->repository.profileRows(snapshot,columns)).isInstanceOf(Failure.class);assertThat(reads).isZero();
    }
    @Test void externalVirtualAndNonOrdinaryBasesFailBeforeReadingRows(){
        external=true;assertThatThrownBy(()->repository.sample(snapshot,columns,10)).isInstanceOf(Failure.class);
        external=false;virtual=true;assertThatThrownBy(()->repository.profileRows(snapshot,columns)).isInstanceOf(Failure.class);
        virtual=false;ordinary=false;assertThatThrownBy(()->repository.profileRows(snapshot,columns)).isInstanceOf(Failure.class);assertThat(reads).isZero();
    }
}
