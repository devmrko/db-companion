package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.GlossaryDocumentController;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GlossaryDocumentServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private int calls, embeddings;
    private String version = "1", response = "";
    private final SessionDataSource source = new SessionDataSource() {
        @Override public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> {
                if (m.getName().equals("getAutoCommit")) return true;
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == int.class) return 0;
                return null;
            });
        }
    };
    private final AiAssistantRepository ai = new AiAssistantRepository(new JdbcTemplate(source)) {
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selection) { return new AiAssistant.Profile(selection, "test", "synthetic", version); }
        @Override public String explain(String owner, String profile, String prompt) { calls++; assertThat(prompt).contains("UNTRUSTED", "sum of transactions"); return response; }
    };
    private final ProfileHistoryRepository profiles = new ProfileHistoryRepository(new JdbcTemplate(source), json, null) {
        @Override public String packageOwner(String owner) { return "TEST_API"; }
    };
    private final VectorSearchRepository models = new VectorSearchRepository(new JdbcTemplate(source)) {
        @Override public boolean modelAvailable(String owner, String model) { return owner.equals("APP") && model.equals("LOCAL_MODEL"); }
    };
    private final GlossaryDocumentRepository vectors = new GlossaryDocumentRepository(new JdbcTemplate(source), json) {
        @Override public double[] embed(VectorSearch.Model model, String text) { embeddings++; return text.contains("transactions") ? new double[]{1, 0} : new double[]{0, 1}; }
    };
    private GlossaryDocumentService service() { return new GlossaryDocumentService(source, ai, profiles, models, vectors, json); }
    private PoolSession session() {
        var session = new PoolSession(new HikariDataSource(), "LOW", () -> {});
        session.initialize(new DatabaseSession(new DatabaseInfo("APP", "OTHER", "LOW", "DB"), List.of("APP", "OTHER")));
        session.metadata().assistant().select(new AiAssistant.Selection("APP", "ASSISTANT")); return session;
    }
    private GlossaryDocument.Source document() {
        return GlossaryDocument.source("guide.txt", List.of(new GlossaryDocumentReader.Section("text", "Revenue is the sum of transactions.")));
    }
    private void validResponse() {
        response = json.writeValueAsString(new GlossaryDocument.Analysis("Summary", List.of(), List.of(new GlossaryDocument.Candidate("DETAIL", "Revenue", List.of(), "Transaction total", "Sum transactions", List.of(new GlossaryDocument.Citation("C1", "sum of transactions"))))));
    }
    @Test void previewMakesNoAiCallAndConsentTokenCanOnlyGenerateOnce() {
        try (var session = session()) {
            validResponse(); var service = service(); var document = document();
            var preview = service.preview(session, document, List.of("C1"), Locale.ENGLISH);
            assertThat(calls).isZero(); assertThat(preview.source()).contains(document.hash(), "C1");
            assertThatThrownBy(() -> service.analyze(session, document, preview.token(), false)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(calls).isZero();
            assertThat(service.analyze(session, document, preview.token(), true).candidates()).hasSize(1);
            assertThat(calls).isEqualTo(1);
            assertThatThrownBy(() -> service.analyze(session, document, preview.token(), true)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(calls).isEqualTo(1);
        }
    }
    @Test void changedProfileAndDocumentStopBeforeAiAndInvalidResponseIsNotRetried() {
        try (var session = session()) {
            var service = service(); var document = document();
            var preview = service.preview(session, document, List.of("C1"), Locale.ENGLISH); version = "2";
            assertThatThrownBy(() -> service.analyze(session, document, preview.token(), true)).isInstanceOf(AiAssistant.Failure.class); assertThat(calls).isZero();
            var other = service.preview(session, document, List.of("C1"), Locale.ENGLISH);
            assertThatThrownBy(() -> service.analyze(session, document(), other.token(), true)).isInstanceOf(AiAssistant.Failure.class); assertThat(calls).isZero();
            var invalid = service.preview(session, document, List.of("C1"), Locale.ENGLISH); response = "not JSON";
            assertThatThrownBy(() -> service.analyze(session, document, invalid.token(), true)).isInstanceOf(AiAssistant.Failure.class); assertThat(calls).isEqualTo(1);
            assertThatThrownBy(() -> service.analyze(session, document, invalid.token(), true)).isInstanceOf(AiAssistant.Failure.class); assertThat(calls).isEqualTo(1);
        }
    }
    @Test void localVectorIndexRequiresAccessibleModelAndDoesNotCallAi() {
        try (var session = session()) {
            var service = service(); var document = document();
            assertThatThrownBy(() -> service.index(session, document, new VectorSearch.Model("OTHER", "LOCAL_MODEL"))).isInstanceOf(AiAssistant.Failure.class);
            assertThat(embeddings).isZero();
            var index = service.index(session, document, new VectorSearch.Model("APP", "LOCAL_MODEL"));
            assertThat(service.search(session, document, index, "transactions").getFirst().score()).isEqualTo(1);
            assertThat(embeddings).isEqualTo(2); assertThat(calls).isZero();
        }
    }
    @Test void controllerSeparatesLoginSessionsAndClearRevokesPendingAiInput() {
        var controller = new GlossaryDocumentController(new GlossaryDocumentReader(), service(), null, json);
        var request = new MockHttpServletRequest();
        var file = new MockMultipartFile("file", "guide.txt", "text/plain", "Revenue is the sum of transactions.".getBytes(StandardCharsets.UTF_8));
        assertThat(controller.upload(file, request).getStatusCode().value()).isEqualTo(401);
        try (var one = session(); var two = session()) {
            request.getSession().setAttribute(PoolSession.ATTRIBUTE, one);
            var doc = (GlossaryDocument.Source) controller.upload(file, request).getBody();
            var preview = (AiAssistant.Preview) controller.preview(new GlossaryDocumentController.Prepare(doc.id(), List.of("C1")), Locale.ENGLISH, request).getBody();
            var otherRequest = new MockHttpServletRequest();
            otherRequest.getSession().setAttribute(PoolSession.ATTRIBUTE, two);
            String key = GlossaryDocumentController.class.getName();
            otherRequest.getSession().setAttribute(key, request.getSession().getAttribute(key));
            assertThat(controller.search(new GlossaryDocumentController.Search(doc.id(), "Revenue", false), otherRequest).getStatusCode().value()).isEqualTo(409);
            assertThat(controller.clear(request).getStatusCode().value()).isEqualTo(200);
            assertThatThrownBy(() -> service().analyze(one, doc, preview.token(), true)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(controller.search(new GlossaryDocumentController.Search(doc.id(), "Revenue", false), request).getStatusCode().value()).isEqualTo(409);
            assertThat(calls).isZero();
        }
    }
}
