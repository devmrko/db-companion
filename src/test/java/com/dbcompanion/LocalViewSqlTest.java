package com.dbcompanion;
import com.dbcompanion.service.LocalViewSql;
import com.dbcompanion.model.Ontology;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class LocalViewSqlTest {
    @Test void acceptsLocalReadOnlyProjectionWithQuotedColumns(){
        assertThat(LocalViewSql.base("LAB","SELECT \"GUID\", \"BASE_DT\" FROM \"SOURCE\".\"USERS\" WITH READ ONLY")).isEqualTo(new LocalViewSql.Base("SOURCE","USERS"));
        assertThat(LocalViewSql.base("LAB","select t.CODE as CODE from USERS t")).isEqualTo(new LocalViewSql.Base("LAB","USERS"));
        assertThat(LocalViewSql.base("LAB","select * from USERS")).isEqualTo(new LocalViewSql.Base("LAB","USERS"));
    }
    @Test void rejectsExpressionsFunctionsLinksJoinsSequencesAndNestedQueries(){
        for(String sql:List.of("select evil() from USERS","select seq.nextval from USERS","select CODE from USERS@remote","select CODE from USERS where evil()=1","select a.CODE from USERS a join O b on a.CODE=b.CODE","select (select CODE from O) from USERS","select CODE+1 from USERS","select /*hint*/ CODE from USERS","select CODE from USERS; drop table USERS"))assertThatThrownBy(()->LocalViewSql.base("LAB",sql)).isInstanceOf(Ontology.Failure.class);
    }
}
