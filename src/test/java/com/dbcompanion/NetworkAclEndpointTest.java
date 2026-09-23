package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.ExternalSourcesService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NetworkAclEndpointTest {
    @Test void quotedHostsPortsAndServicesAreResolvedFromAddressStructure(){
        var parsed=LinkEndpoint.parse("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=\"mysql.example.test\")(PORT='3306'))(CONNECT_DATA=(SERVICE_NAME=MYSQL)))");
        assertThat(parsed.endpoints()).containsExactly("tcp://mysql.example.test:3306");
        assertThat(parsed.service()).isEqualTo("MYSQL");assertThat(parsed.status()).isEqualTo("PARSED");
        assertThat(LinkEndpoint.parse("(description = (address = (protocol = tcps)(host = 'adb.example')(port=1522)))").endpoints()).containsExactly("tcps://adb.example:1522");
    }
    @Test void multipleAddressesRetainTheirOwnPortAndProtocol(){
        var parsed=LinkEndpoint.parse("(DESCRIPTION_LIST=(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=first)(PORT=1521))(ADDRESS=(PROTOCOL=TCPS)(HOST=second)(PORT=1522)))(CONNECT_DATA=(SERVICE_NAME=db_low))))");
        assertThat(parsed.endpoints()).containsExactly("tcp://first:1521","tcps://second:1522");assertThat(parsed.service()).isEqualTo("db_low");
    }
    @Test void ipv6AndEasyConnectDoNotLeakAuthenticationOrQuery(){
        assertThat(LinkEndpoint.parse("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST='2001:db8::1')(PORT=1521)))").endpoints()).containsExactly("tcp://[2001:db8::1]:1521");
        var easy=LinkEndpoint.parse("tcps://user:SECRET@[2001:db8::1]:1522/db_low?token=SECRET#SECRET");
        assertThat(easy.endpoints()).containsExactly("tcps://[2001:db8::1]:1522");assertThat(easy.service()).isEqualTo("db_low");assertThat(easy.toString()).doesNotContain("SECRET","user");
        assertThat(LinkEndpoint.parse("//host:1521/service").endpoints()).containsExactly("tcp://host:1521");
        assertThat(LinkEndpoint.parse("host:1521/service").service()).isEqualTo("service");
    }
    @Test void passwordContentsCannotBecomeAnEndpoint(){
        String descriptor="(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=actual)(PORT=3306))(SECURITY=(HOST=SECRET))(PASSWORD=\"(HOST=LEAK)(PORT=9999)\"))";
        var parsed=LinkEndpoint.parse(descriptor);assertThat(parsed.endpoints()).containsExactly("tcp://actual:3306");
        assertThat(parsed.toString()).doesNotContain("SECRET","LEAK","9999");
        assertThat(LinkEndpoint.parse("(PASSWORD=(ADDRESS=(HOST=SECRET)(PORT=3306)))").endpoints()).isEmpty();
    }
    @Test void aliasesMissingAndMalformedMetadataAreNotInventedAsHosts(){
        assertThat(LinkEndpoint.parse("my_tns_alias").status()).isEqualTo("ALIAS");assertThat(LinkEndpoint.parse("my_tns_alias").endpoints()).isEmpty();
        assertThat(LinkEndpoint.parse(null).status()).isEqualTo("UNAVAILABLE");
        assertThat(LinkEndpoint.parse("(DESCRIPTION=(ADDRESS=(PORT=3306))(CONNECT_DATA=(SERVICE_NAME=MYSQL)))").status()).isEqualTo("UNAVAILABLE");
        for(String raw:List.of("(DESCRIPTION=(ADDRESS=(HOST='broken)))","(DESCRIPTION=(ADDRESS=(HOST=ok)))SECRET","x".repeat(32001),"(DESCRIPTION=".repeat(40)+"(HOST=SECRET)"+")".repeat(40),"javascript://host/SECRET")){
            assertThat(LinkEndpoint.parse(raw).status()).isEqualTo("UNRECOGNIZED");assertThat(LinkEndpoint.parse(raw).toString()).doesNotContain("SECRET");
        }
        assertThat(LinkEndpoint.parse("tcp://host:99999/svc").endpoints()).isEmpty();
    }
    private AclCatalog catalog(String status){return new AclCatalog(List.of(new Ace("host","443","443","APP","DATABASE","http","GRANT",null,"1",null,null,"NO")),"SYS.DBA_HOST_ACES","DATABASE",status,"");}
    @Test void aclCacheIsLoginWideImmutableAndRefreshDoesNotChangeObjectCaches(){
        var state=new State();var calls=new AtomicInteger();
        var links=state.list("APP",Kind.links,false,()->new Catalog(List.of(),"USER_DB_LINKS","AVAILABLE",""));
        for(int i=0;i<3;i++)state.acl(false,()->{calls.incrementAndGet();return catalog("AVAILABLE");});assertThat(calls).hasValue(1);
        state.list("APP",Kind.tables,true,()->new Catalog(List.of(),"USER_EXTERNAL_TABLES","AVAILABLE",""));
        state.acl(false,()->{calls.incrementAndGet();return catalog("AVAILABLE");});assertThat(calls).hasValue(1);
        state.acl(true,()->{calls.incrementAndGet();return catalog("AVAILABLE");});assertThat(calls).hasValue(2);
        assertThat(state.list("APP",Kind.links,false,()->{throw new IllegalStateException();})).isSameAs(links);
        assertThatThrownBy(()->catalog("AVAILABLE").items().clear()).isInstanceOf(UnsupportedOperationException.class);
        new State().acl(false,()->{calls.incrementAndGet();return catalog("AVAILABLE");});assertThat(calls).hasValue(3);
    }
    @Test void aclRefreshClearsPriorDataEvenOnFailure(){
        var state=new State();var calls=new AtomicInteger();state.acl(false,()->catalog("AVAILABLE"));
        assertThatThrownBy(()->state.acl(true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        for(int i=0;i<2;i++)state.acl(false,()->{calls.incrementAndGet();return catalog("ERROR");});assertThat(calls).hasValue(2);
        for(int i=0;i<2;i++)state.acl(false,()->{calls.incrementAndGet();return catalog("ACCESS_REQUIRED");});assertThat(calls).hasValue(3);
    }
    @Test void userProjectionDoesNotAssumeDbaColumnsOrSelectedSchema(){
        var user=new AgentViewColumns(List.of("HOST","LOWER_PORT","UPPER_PORT","PRIVILEGE","STATUS"));
        String sql=ExternalSourcesRepository.aclSql(true,user);
        assertThat(sql).contains("SYS.USER_HOST_ACES","SYS_CONTEXT('USERENV','SESSION_USER')","\"STATUS\"","NULL").doesNotContain("\"ACE_ORDER\"","\"GRANT_TYPE\"","\"PRINCIPAL\"","OWNER =");
        var dba=new AgentViewColumns(List.of("HOST","LOWER_PORT","UPPER_PORT","PRINCIPAL","PRINCIPAL_TYPE","PRIVILEGE","GRANT_TYPE","ACE_ORDER","START_DATE","END_DATE","INVERTED_PRINCIPAL"));
        assertThat(ExternalSourcesRepository.aclSql(false,dba)).contains("SYS.DBA_HOST_ACES","\"ACE_ORDER\"","\"GRANT_TYPE\"","\"PRINCIPAL\"").doesNotContain("\"STATUS\"");
    }
    @Test void realServiceAclCacheHitNeverBorrowsConnectionOrUsesSelectedSchema(){
        var source=new SessionDataSource();var service=new ExternalSourcesService(source,new ExternalSourcesRepository(new org.springframework.jdbc.core.JdbcTemplate(source)));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new PoolSession(pool,"LOW",()->{})){
            var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));login.initialize(state);
            var stored=state.externalSources().acl(false,()->catalog("AVAILABLE"));state.selectSchema("OTHER");
            assertThat(service.acl(login,false)).isSameAs(stored);assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void aclImplementationIsReadOnlyAndFallbackIsAccessErrorsOnly() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/ExternalSourcesRepository.java"));
        assertThat(source).contains("return result.status().equals(\"ACCESS_REQUIRED\")?acl(true):result","columns(view)","USER\":\"DBA\")+\"_HOST_ACES");
        assertThat(source).doesNotContain("APPEND_HOST_ACE","REMOVE_HOST_ACE","jdbc.update(","jdbc.execute(");
    }
}
