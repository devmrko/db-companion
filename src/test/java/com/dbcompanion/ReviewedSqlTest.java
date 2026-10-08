package com.dbcompanion;

import com.dbcompanion.service.*;
import com.dbcompanion.model.Ontology.Failure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewedSqlTest {
    @Test void independentUnionBranchesCannotBorrowAliasesOrAuthorizeJoins(){
        var isolated=new ReviewedSql.Scope("APP",scope().tables(),List.of());
        String left="SELECT 'standard' AS metric, COUNT(DISTINCT o.ID) AS amount FROM APP.ORDERS o WHERE TRIM(o.BUYER_NO) = '1'";
        String right="SELECT 'business' AS metric, COUNT(DISTINCT c.CUSTOMER_ID) AS amount FROM APP.CUSTOMER c";
        assertThat(ReviewedSql.check(left+" UNION ALL "+right,isolated).tables()).containsExactly("ORDERS","CUSTOMER");
        for(String sql:List.of(left+" UNION "+right,left+" UNION ALL DELETE FROM ORDERS",left+" UNION ALL SELECT o.ID FROM CUSTOMER c",left+" UNION ALL SELECT c.CUSTOMER_ID FROM CUSTOMER c JOIN ORDERS o ON o.ID=c.CUSTOMER_ID",left+" UNION ALL",left+" UNION ALL SELECT x.ID FROM OTHER.ORDERS x"))
            assertThatThrownBy(()->ReviewedSql.check(sql,isolated)).isInstanceOf(Failure.class);
    }
    OntologyInquiry.Evidence relation(List<String> from,List<String> to){return new OntologyInquiry.Evidence("R1","RELATION","ORDERS","CUSTOMER",from,to,"buyer","","FK",true,List.of(),List.of(),"now");}
    ReviewedSql.Scope scope(List<String> from,List<String> to){return new ReviewedSql.Scope("APP",Map.of(
        "ORDERS",new ReviewedSql.Table("ORDERS",Set.of("ID","BUYER_NO","TENANT_ID","AMOUNT","CREATED_AT")),
        "CUSTOMER",new ReviewedSql.Table("CUSTOMER",Set.of("CUSTOMER_ID","TENANT_ID","REGION","DISPLAY_NAME"))),List.of(relation(from,to)));}
    ReviewedSql.Scope scope(){return scope(List.of("BUYER_NO"),List.of("CUSTOMER_ID"));}
    @Test void exactSelectAggregatesFiltersAndVerifiedJoins(){
        for(String sql:List.of(
            "SELECT o.ID, o.AMOUNT FROM APP.ORDERS o WHERE o.AMOUNT >= 1 ORDER BY o.ID DESC FETCH FIRST 10 ROWS ONLY",
            "SELECT COUNT(*) AS n FROM ORDERS o",
            "SELECT c.REGION, SUM(o.AMOUNT), COUNT(DISTINCT o.BUYER_NO) FROM ORDERS o JOIN CUSTOMER c ON c.CUSTOMER_ID=o.BUYER_NO GROUP BY c.REGION HAVING SUM(o.AMOUNT)>0 ORDER BY c.REGION NULLS LAST",
            "SELECT NVL(o.AMOUNT,0)/NULLIF(o.ID,0) FROM ORDERS o WHERE o.CREATED_AT BETWEEN DATE '2026-01-01' AND DATE '2026-02-01' AND (o.ID IN (1,2) OR o.BUYER_NO IS NULL)",
            "SELECT c.DISPLAY_NAME FROM CUSTOMER c LEFT OUTER JOIN ORDERS o ON o.BUYER_NO=c.CUSTOMER_ID WHERE c.DISPLAY_NAME NOT LIKE 'O''Brien'",
            "SELECT \"c\".\"DISPLAY_NAME\" FROM \"APP\".\"CUSTOMER\" \"c\" WHERE NOT \"c\".\"REGION\" = 'select; -- DROP TABLE'",
            "SELECT TRUNC(o.CREATED_AT), SUM(ABS(-o.AMOUNT)) FROM ORDERS o GROUP BY TRUNC(o.CREATED_AT)")){
            assertThat(ReviewedSql.check(sql,scope()).sql()).isEqualTo(sql);
        }
        assertThat(ReviewedSql.check("```sql\nSELECT COUNT(*) FROM ORDERS o;\n```",scope()).sql()).isEqualTo("SELECT COUNT(*) FROM ORDERS o");
    }
    @Test void rejectsMutationSqlExtensionsAndHiddenFunctions(){
        for(String sql:List.of(
            "DELETE FROM ORDERS", "BEGIN NULL; END;", "SELECT AI runsql delete from ORDERS",
            "SELECT o.ID FROM ORDERS o; DELETE FROM ORDERS", "SELECT /*+ PARALLEL(99) */ o.ID FROM ORDERS o",
            "SELECT o.ID FROM ORDERS o --comment", "SELECT * FROM ORDERS", "SELECT o.* FROM ORDERS o",
            "SELECT USER_FUNC(o.ID) FROM ORDERS o", "SELECT APP.F(o.ID) FROM ORDERS o", "SELECT SYS.UTL_HTTP.REQUEST('x') FROM ORDERS o",
            "WITH x AS (SELECT ID FROM ORDERS) SELECT x.ID FROM x", "SELECT (SELECT COUNT(*) FROM CUSTOMER c) FROM ORDERS o",
            "SELECT o.ID FROM ORDERS o UNION SELECT c.CUSTOMER_ID FROM CUSTOMER c", "SELECT o.ID FROM ORDERS@REMOTE o",
            "SELECT o.ID FROM OTHER.ORDERS o", "SELECT o.ID FROM UNKNOWN o", "SELECT c.EMAIL FROM CUSTOMER c",
            "SELECT ID FROM ORDERS", "SELECT o.ID FROM ORDERS o FOR UPDATE", "SELECT o.ID INTO :b FROM ORDERS o",
            "SELECT COUNT(*) OVER() FROM ORDERS o", "SELECT CASE WHEN o.ID=1 THEN 0 ELSE 1 END FROM ORDERS o",
            "SELECT o.ID FROM ORDERS o WHERE EXISTS (SELECT 1 FROM CUSTOMER c)", "SELECT o.ID FROM ORDERS o, CUSTOMER c",
            "SELECT o.ID FROM ORDERS o CROSS JOIN CUSTOMER c", "SELECT o.ID FROM ORDERS o JOIN CUSTOMER c ON 1=1",
            "SELECT o.ID FROM ORDERS o JOIN CUSTOMER c ON o.ID=c.CUSTOMER_ID", "SELECT o.ID FROM ORDERS o JOIN CUSTOMER c ON o.BUYER_NO=c.CUSTOMER_ID OR 1=1",
            "SELECT \"upper\"(c.DISPLAY_NAME) FROM CUSTOMER c", "SELECT o.ID FROM ORDERS o CONNECT BY LEVEL<9")){
            assertThatThrownBy(()->ReviewedSql.check(sql,scope())).as(sql).isInstanceOf(Failure.class);
        }
    }
    @Test void compositeJoinsMustBeCompleteAndUseOnePriorTable(){
        var s=scope(List.of("TENANT_ID","BUYER_NO"),List.of("TENANT_ID","CUSTOMER_ID"));
        String good="SELECT o.ID FROM ORDERS o JOIN CUSTOMER c ON o.BUYER_NO=c.CUSTOMER_ID AND c.TENANT_ID=o.TENANT_ID";
        assertThat(ReviewedSql.check(good,s).tables()).containsExactly("ORDERS","CUSTOMER");
        for(String on:List.of("o.BUYER_NO=c.CUSTOMER_ID","o.BUYER_NO=c.CUSTOMER_ID AND o.BUYER_NO=c.CUSTOMER_ID","o.BUYER_NO=c.CUSTOMER_ID AND c.TENANT_ID=c.TENANT_ID"))
            assertThatThrownBy(()->ReviewedSql.check("SELECT o.ID FROM ORDERS o JOIN CUSTOMER c ON "+on,s)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->ReviewedSql.check(good,new ReviewedSql.Scope("APP",s.tables(),List.of()))).isInstanceOf(Failure.class);
    }
    @Test void limitsRejectDeepAndLargeInputWithoutStackOverflow(){
        for(String sql:List.of("SELECT "+"(".repeat(100)+"o.ID"+")".repeat(100)+" FROM ORDERS o","SELECT "+"-".repeat(100)+"o.ID FROM ORDERS o","SELECT o.ID FROM ORDERS o WHERE "+"NOT ".repeat(100)+"o.ID=1","SELECT '"+"x".repeat(20000)+"' FROM ORDERS o","SELECT o.ID FROM ORDERS o WHERE o.ID IN ("+String.join(",",Collections.nCopies(101,"1"))+")"))
            assertThatThrownBy(()->ReviewedSql.check(sql,scope())).isInstanceOf(Failure.class);
    }
    @Test void scalarTypesExcludeObjectsClobsVectorsAndFunctions(){
        for(String type:List.of("NUMBER(18,2)","VARCHAR2(120)","TIMESTAMP(6) WITH LOCAL TIME ZONE","DATE"))assertThat(ReviewedSql.scalar(type)).isTrue();
        for(String type:List.of("CLOB","BLOB","VECTOR","XMLTYPE","APP.MY_TYPE","JSON"))assertThat(ReviewedSql.scalar(type)).isFalse();
    }
}
