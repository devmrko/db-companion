package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.ExternalSourcesController;
import com.dbcompanion.model.*;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.model.SchemaAcl.*;
import com.dbcompanion.repository.ExternalSourcesRepository;
import com.dbcompanion.service.ExternalSourcesService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SchemaAclTest {
    private Ace ace(String principal){return new Ace("*.example.test","443","443",principal,"DATABASE","HTTP","GRANT",null,"1","2020-01-01","2021-01-01","NO");}
    private AclCatalog raw(Ace... items){return new AclCatalog(List.of(items),"SYS.DBA_HOST_ACES","DATABASE","AVAILABLE","");}
    private Roles roles(Grant... items){return new Roles(List.of(items),"AVAILABLE","");}
    private SchemaAcl.Catalog mapped(String schema){return SchemaAcl.map(schema,raw(ace(schema)),roles());}

    @Test void directNestedRolePublicAndPublicRoleRemainSeparate(){
        var result=SchemaAcl.map("APP",raw(ace("APP"),ace("PUBLIC"),ace("R2"),ace("UNRELATED")),
            roles(new Grant("APP","R1","NO"),new Grant("R1","R2","YES"),new Grant("PUBLIC","R2","YES")));
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.items()).extracting(Row::route).containsExactly("DIRECT","PUBLIC","ROLE","PUBLIC_ROLE");
        assertThat(result.items().get(2).path()).containsExactly("APP","R1","R2");
        assertThat(result.items().get(2).defaultRole()).isEqualTo("NO");
        assertThat(result.items().get(3).path()).containsExactly("PUBLIC","R2");
        assertThat(result.items()).allMatch(row->row.schema().equals("APP"));
    }
    @Test void shortestDeterministicRepresentativePathHandlesDiamondsCyclesAndQuotedNames(){
        var grants=roles(new Grant("Mixed User","Z","YES"),new Grant("Mixed User","A","NO"),
            new Grant("Z","END","YES"),new Grant("A","END","YES"),new Grant("END","Mixed User","YES"));
        var result=SchemaAcl.map("Mixed User",raw(ace("END"),ace("MIXED USER")),grants);
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().path()).containsExactly("Mixed User","A","END");
        var directRole=SchemaAcl.map("Mixed User",raw(ace("A")),grants);
        assertThat(directRole.items().getFirst().path()).containsExactly("Mixed User","A");
    }
    @Test void denialAndExpiredRulesAreNotFilteredOrInterpretedAsConnectionSuccess(){
        var deny=new Ace("host",null,null,"APP","DATABASE","CONNECT","DENY",null,"2","2020","2021","NO");
        var result=SchemaAcl.map("APP",raw(deny),roles());
        assertThat(result.items().getFirst().ace()).isSameAs(deny);
        assertThat(result.items().getFirst().ace().grantType()).isEqualTo("DENY");
    }
    @Test void inversionNonDatabaseAndMissingMetadataAreNeverAssignedToSchema(){
        var entries=new ArrayList<Ace>();
        for(String type:Arrays.asList("XS",null))entries.add(new Ace("host",null,null,"APP",type,"HTTP","GRANT",null,null,null,null,"NO"));
        for(String inverted:Arrays.asList("YES",null))entries.add(new Ace("host",null,null,"APP","DATABASE","HTTP","GRANT",null,null,null,null,inverted));
        entries.add(ace(null));
        var result=SchemaAcl.map("APP",raw(entries.toArray(Ace[]::new)),roles());
        assertThat(result.items()).isEmpty();assertThat(result.review()).containsExactlyElementsOf(entries);
        assertThat(result.status()).isEqualTo("PARTIAL");
    }
    @Test void failedRoleReadRetainsDirectPublicButNeverUsesIncompleteRoleEdges(){
        for(String status:List.of("ACCESS_REQUIRED","UNSUPPORTED","ERROR","LIMIT")){
            var result=SchemaAcl.map("APP",raw(ace("APP"),ace("PUBLIC"),ace("ROLE")),new Roles(List.of(new Grant("APP","ROLE","YES")),status,"ORA-00942"));
            assertThat(result.items()).extracting(Row::route).containsExactly("DIRECT","PUBLIC");
            assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.roleStatus()).isEqualTo(status);
            assertThat(result.error()).isEqualTo("ORA-00942");
            assertThat(result.cacheable()).isEqualTo(Set.of("ACCESS_REQUIRED","UNSUPPORTED").contains(status));
        }
    }
    @Test void userFallbackAndFailedRawReadDoNotInventSchemaMapping(){
        var fallback=new AclCatalog(List.of(ace("LOGIN")),"SYS.USER_HOST_ACES","USER","AVAILABLE","");
        var result=SchemaAcl.map("OTHER",fallback,roles());
        assertThat(result.status()).isEqualTo("MAPPING_UNAVAILABLE");assertThat(result.items()).isEmpty();
        var failed=SchemaAcl.map("APP",new AclCatalog(List.of(),"SYS.USER_HOST_ACES","USER","ERROR","ORA-01013"),roles());
        assertThat(failed.status()).isEqualTo("ERROR");assertThat(failed.error()).isEqualTo("ORA-01013");assertThat(failed.cacheable()).isFalse();
    }
    @Test void mappingAndRoleCollectionsAreImmutable(){
        assertThatThrownBy(()->mapped("APP").items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->mapped("APP").items().getFirst().path().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->roles().items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void walkBatchesAtMostTwoHundredAndVisitsEachPrincipalOnce(){
        var walk=new Walk("APP");var first=walk.next();assertThat(first).containsExactly("APP","PUBLIC");
        var grants=new ArrayList<Grant>();for(int i=0;i<250;i++)grants.add(new Grant("APP","R"+i,"YES"));
        walk.accept(first,grants);var second=walk.next();assertThat(second).hasSize(200);
        walk.accept(second,List.of(new Grant("R0","APP","NO"),new Grant("R1","R2","YES")));
        assertThat(walk.next()).hasSize(50);assertThat(walk.next()).isEmpty();assertThat(walk.grants()).hasSize(252);
        assertThatThrownBy(()->walk.accept(List.of("APP"),List.of(new Grant("OTHER","R","YES")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void walkStopsAtNodeDepthAndEdgeBudgets(){
        var nodes=new Walk("APP");var initial=nodes.next();var many=new ArrayList<Grant>();
        for(int i=0;i<999;i++)many.add(new Grant("APP","R"+i,"YES"));
        assertThatThrownBy(()->nodes.accept(initial,many)).isInstanceOf(SchemaAcl.LimitExceeded.class);
        var depth=new Walk("APP");depth.accept(depth.next(),List.of(new Grant("APP","R1","YES")));
        for(int i=1;i<32;i++)depth.accept(depth.next(),List.of(new Grant("R"+i,"R"+(i+1),"YES")));
        assertThatThrownBy(()->depth.accept(depth.next(),List.of(new Grant("R32","R33","YES")))).isInstanceOf(SchemaAcl.LimitExceeded.class);
        var edges=new Walk("APP");var roots=edges.next();var initialEdges=new ArrayList<Grant>();
        for(int i=0;i<100;i++)initialEdges.add(new Grant("APP","R"+i,"YES"));edges.accept(roots,initialEdges);
        var batch=edges.next();var dense=new ArrayList<Grant>();
        for(String from:batch)for(String to:batch)dense.add(new Grant(from,to,"YES"));
        assertThatThrownBy(()->edges.accept(batch,dense)).isInstanceOf(SchemaAcl.LimitExceeded.class);
    }
    @Test void roleQueriesUseBoundFrontiersNotSessionRolesOrWholeDatabaseScan(){
        String sql=ExternalSourcesRepository.roleSql(2);
        assertThat(sql).contains("SYS.DBA_ROLE_PRIVS WHERE GRANTEE IN (?,?)","GRANTED_ROLE","DEFAULT_ROLE").doesNotContain("SESSION_ROLES","CONNECT BY");
        assertThat(ExternalSourcesRepository.roleSql(200).chars().filter(c->c=='?').count()).isEqualTo(200);
        assertThatThrownBy(()->ExternalSourcesRepository.roleSql(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExternalSourcesRepository.roleSql(201)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cacheIsSchemaAndLoginSpecificAndEitherRefreshClearsRawAndDerivedBeforeLoading(){
        var state=new State();var calls=new AtomicInteger();
        var link=state.list("APP",Kind.links,false,()->new ExternalSources.Catalog(List.of(),"SRC","AVAILABLE",""));
        state.acl(false,()->raw(ace("APP")));
        for(String schema:List.of("APP","OTHER","APP"))state.schemaAcl(schema,false,()->{calls.incrementAndGet();return mapped(schema);});
        assertThat(calls).hasValue(2);
        assertThatThrownBy(()->state.acl(true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        state.schemaAcl("APP",false,()->{calls.incrementAndGet();return mapped("APP");});assertThat(calls).hasValue(3);
        state.acl(false,()->raw(ace("APP")));
        assertThatThrownBy(()->state.schemaAcl("APP",true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        state.acl(false,()->{calls.incrementAndGet();return raw();});assertThat(calls).hasValue(4);
        state.schemaAcl("APP",false,()->{calls.incrementAndGet();return mapped("APP");});assertThat(calls).hasValue(5);
        new State().schemaAcl("APP",false,()->{calls.incrementAndGet();return mapped("APP");});assertThat(calls).hasValue(6);
        assertThat(state.list("APP",Kind.links,false,()->{throw new IllegalStateException();})).isSameAs(link);
    }
    @Test void realServiceCacheHitDoesNotInitializeHikariAndStaleSchemaIsRejected(){
        var source=new SessionDataSource();var service=new ExternalSourcesService(source,new ExternalSourcesRepository(new org.springframework.jdbc.core.JdbcTemplate(source)));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new PoolSession(pool,"LOW",()->{})){
            var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));login.initialize(state);
            var value=state.externalSources().schemaAcl("APP",false,()->mapped("APP"));
            assertThat(service.schemaAcl(login,"APP",false)).isSameAs(value);
            assertThatThrownBy(()->service.schemaAcl(login,"OTHER",false)).isInstanceOf(Failure.class);
            state.selectSchema("OTHER");var other=state.externalSources().schemaAcl("OTHER",false,()->mapped("OTHER"));
            assertThat(service.schemaAcl(login,"OTHER",false)).isSameAs(other);
            assertThatThrownBy(()->service.schemaAcl(login,"APP",false)).isInstanceOf(Failure.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void returnNavigationOnlyAcceptsKnownLocalViews(){
        for(String tab:List.of("links","tables","acl"))for(String view:List.of("schema","all")){
            String path="/db/external-sources?tab="+tab+"&aclView="+view;
            assertThat(ExternalSourcesController.safeReturn(path)).isTrue();
            assertThat(com.dbcompanion.controller.LanguageController.safeReturn(path)).isEqualTo(path);
        }
        for(String path:List.of("//evil.test","/db/external-sources?tab=bad&aclView=all","/db/external-sources?tab=acl&aclView=all&next=evil","/db/external-sources/acl/schema"))assertThat(ExternalSourcesController.safeReturn(path)).isFalse();
        assertThat(ExternalSourcesController.safeReturn(null)).isFalse();
    }
}
