package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiMemoryPolicy;
import com.dbcompanion.model.AiMemoryPolicy.*;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.repository.AiMemoryPolicyRepository;
import com.dbcompanion.service.AiMemoryPolicyService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AiMemoryPolicyTest {
    @Test void creationReadbackAcceptsTheSameQualifiedProfileButNeverADifferentOwner() {
        assertThat(AiMemoryPolicyRepository.profileMatches("PROFILE","APP","PROFILE")).isTrue();
        assertThat(AiMemoryPolicyRepository.profileMatches("\"APP\".\"PROFILE\"","APP","PROFILE")).isTrue();
        assertThat(AiMemoryPolicyRepository.profileMatches("\"OTHER\".\"PROFILE\"","APP","PROFILE")).isFalse();
        assertThat(AiMemoryPolicyRepository.profileMatches("\"APP\".\"OTHER\"","APP","PROFILE")).isFalse();
        assertThat(AiMemoryPolicyRepository.profileMatches(null,"APP","PROFILE")).isFalse();
    }
    @Test void nativeMemoryCreationAcceptsBothObservedProfileParameterNames() {
        for (String name : List.of("PROFILE_NAME", "MEMORY_PROFILE_NAME")) {
            assertThat(AiMemoryPolicyRepository.memoryCreateSignature(List.of("MEMORY_STORE_NAME:VARCHAR2:IN", name + ":VARCHAR2:IN", "DESCRIPTION:CLOB:IN", "ATTRIBUTES:CLOB:IN"))).isTrue();
        }
        assertThat(AiMemoryPolicyRepository.memoryCreateSignature(List.of("MEMORY_STORE_NAME:VARCHAR2:IN", "PROFILE_NAME:NUMBER:IN", "DESCRIPTION:CLOB:IN", "ATTRIBUTES:CLOB:IN"))).isFalse();
        assertThat(AiMemoryPolicyRepository.memoryCreateSignature(List.of())).isFalse();
    }
    int writes;
    int extracts;
    boolean supported = true, matches = true, fails;
    final SessionDataSource source = new SessionDataSource();
    final AiMemoryPolicyRepository repository = new AiMemoryPolicyRepository(new JdbcTemplate(source)) {
        @Override public Capability capability() { return new Capability("23.26.4.1.0", "API_OWNER", supported, supported, "USER_AI_GUARDRAILS", ""); }
        @Override public List<Resource> stores(Capability c) { return List.of(); }
        @Override public String attributes(Create input) { return "{\"match_limit\":5}"; }
        @Override public void create(Capability c, Create input, String attributes) { writes++; if (fails) throw new IllegalStateException("uncertain",new java.sql.SQLException("private-provider-response", "", 20000)); }
        @Override public boolean matches(Capability c, Create input, String attributes) { return matches; }
        @Override public void validateExtraction(Extract input) {
            if (!supported) throw AiMemoryPolicy.error(409, "unsupported");
        }
        @Override public void extract(Extract input) { extracts++; if (fails) throw new IllegalStateException("uncertain"); }
    };
    final AiMemoryPolicyService service = new AiMemoryPolicyService(source, repository);
    PoolSession session() {
        var value = new PoolSession(new HikariDataSource(), "TEST", () -> {});
        value.initialize(new DatabaseSession(new DatabaseInfo("APP", "APP", "TEST", "SYNTHETIC"), List.of("APP", "OTHER")));
        return value;
    }
    Create input(String schema, boolean consent, int limit) {
        return new Create(schema, "memory", "TEST_STORE", "TEST_PROFILE", "", limit, null, null, false, false, false, consent);
    }
    @Test void consentRangesAndNamesAreCheckedBeforeAnyWrite() {
        try (var s = session()) {
            assertThatThrownBy(() -> service.create(s, input("APP", false, 5))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.create(s, input("APP", true, 100))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> AiMemoryPolicy.name("STORE';END;")).isInstanceOf(RuntimeException.class);
            assertThat(writes).isZero();
        }
    }
    @Test void memoryStoreNameLimitIsCheckedBeforeDatabaseWritesWithoutRestrictingProfilesOrGuardrails() {
        try(var s=session()) {
            for(String invalid:List.of("A".repeat(17),"STORE$NAME","STORE#NAME")) {
                var value=new Create("APP","memory",invalid,"PROFILE".repeat(3),"",5,null,null,false,false,false,true);
                assertThatThrownBy(()->service.create(s,value)).isInstanceOfSatisfying(com.dbcompanion.common.exception.MetadataEditException.class,
                        ex->assertThat(ex.userMessage()).contains("16"));
            }
            assertThat(writes).isZero();
            assertThatCode(()->AiMemoryPolicy.validate(new Create("APP","memory","A".repeat(16),"PROFILE".repeat(3),"",5,null,null,false,false,false,true))).doesNotThrowAnyException();
            assertThatCode(()->AiMemoryPolicy.validate(new Create("APP","guardrail","A".repeat(30),"PROFILE","",null,null,"detect",true,false,false,true))).doesNotThrowAnyException();
        }
    }
    @Test void unsupportedAndForeignSchemaCannotWrite() {
        try (var s = session()) {
            supported = false;
            assertThatThrownBy(() -> service.create(s, input("APP", true, 5))).isInstanceOf(RuntimeException.class);
            supported = true; s.metadata().selectSchema("OTHER");
            assertThatThrownBy(() -> service.create(s, input("OTHER", true, 5))).isInstanceOf(RuntimeException.class);
            assertThat(writes).isZero();
        }
    }
    @Test void committedButUnverifiedCallIsNotRetried() {
        try (var s = session()) {
            matches = false;
            assertThat(service.create(s, input("APP", true, 5)).verified()).isFalse();
            assertThat(writes).isEqualTo(1);
            fails = true;
            assertThat(service.create(s, input("APP", true, 5)).verified()).isFalse();
            assertThat(writes).isEqualTo(2);
        }
    }
    @Test void verifiedResultRequiresReadback() {
        try (var s = session()) { assertThat(service.create(s, input("APP", true, 5)).verified()).isTrue(); }
    }
    @Test void uncertainCreationReportsOnlySafeOracleCodeWithoutRetrying() {
        try(var s=session()) {
            fails=true;
            var result=service.create(s,input("APP",true,5));
            assertThat(result.verified()).isFalse();
            assertThat(result.message()).contains("ORA-20000").doesNotContain("private-provider-response","uncertain");
            assertThat(writes).isEqualTo(1);
        }
    }
    @Test void extractionRequiresConsentAndOwnerBeforeCallingProvider() {
        try (var s = session()) {
            assertThatThrownBy(() -> service.extract(s, new Extract("APP", "PROFILE", "STORE", "conv-1", false))).isInstanceOf(RuntimeException.class);
            s.metadata().selectSchema("OTHER");
            assertThatThrownBy(() -> service.extract(s, new Extract("OTHER", "PROFILE", "STORE", "conv-1", true))).isInstanceOf(RuntimeException.class);
            assertThat(extracts).isZero();
        }
    }
    @Test void uncertainExtractionIsNotRetriedAndDoesNotReportSuccess() {
        try (var s = session()) {
            fails = true;
            assertThat(service.extract(s, new Extract("APP", "PROFILE", "STORE", "conv-1", true)).verified()).isFalse();
            assertThat(extracts).isEqualTo(1);
        }
    }
    @Test void guardrailRequiresAtLeastOneCheckAndKnownMode() {
        var input = new Create("APP", "guardrail", "POLICY", "OCI_PROFILE", "", null, null, "detect", false, false, false, true);
        assertThatThrownBy(() -> AiMemoryPolicy.validate(input)).isInstanceOf(RuntimeException.class);
    }
    @Test void unlinkAllowsOnlyExplicitEmptyLinkAndPreservesOtherFields() {
        var target = new com.dbcompanion.model.ProfileEdit.Target("APP", "PROFILE", "guardrail");
        var json = new tools.jackson.databind.json.JsonMapper();
        com.dbcompanion.common.db.ProfileEditPolicy.input(target, "", json);
        assertThat(com.dbcompanion.common.db.ProfileEditPolicy.equivalent("guardrail", "", null, json)).isTrue();
        assertThat(com.dbcompanion.common.db.ProfileEditPolicy.equivalent("model", "", null, json)).isFalse();
        assertThatThrownBy(() -> com.dbcompanion.common.db.ProfileEditPolicy.input(target, " ", json)).isInstanceOf(RuntimeException.class);
    }
}
