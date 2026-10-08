package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GlossaryDocumentService {
    private static final String PURPOSE = "glossary-document";
    private final SessionDataSource source;
    private final AiAssistantRepository assistant;
    private final ProfileHistoryRepository profiles;
    private final VectorSearchRepository models;
    private final GlossaryDocumentRepository vectors;
    private final JsonMapper json;
    private final TransactionTemplate read, generate;
    public GlossaryDocumentService(SessionDataSource source, AiAssistantRepository assistant, ProfileHistoryRepository profiles,
                                   VectorSearchRepository models, GlossaryDocumentRepository vectors, JsonMapper json) {
        this.source = source; this.assistant = assistant; this.profiles = profiles; this.models = models; this.vectors = vectors; this.json = json;
        var manager = new DataSourceTransactionManager(source);
        read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(60);
        generate = new TransactionTemplate(manager); generate.setReadOnly(true); generate.setTimeout(90);
    }
    private <T> T query(PoolSession session, boolean ai, Supplier<T> action) {
        source.bind(session.pool(), session.metadata().info().username());
        try { return (ai ? generate : read).execute(status -> action.get()); } finally { source.clear(); }
    }
    public List<VectorSearch.Model> models(PoolSession session) { return query(session, false, models::models); }
    public record Embeddings(VectorSearch.Model model, List<double[]> values) {}
    public Embeddings index(PoolSession session, GlossaryDocument.Source document, VectorSearch.Model model) {
        return query(session, false, () -> {
            if (model == null || !models.modelAvailable(model.owner(), model.name())) throw BusinessGlossary.failure(422, "사용 가능한 로컬 임베딩 모델을 선택해 주세요.");
            var result = document.chunks().stream().map(c -> vectors.embed(model, c.text())).toList();
            int dimensions = result.getFirst().length;
            if (result.stream().anyMatch(v -> v.length != dimensions)) throw BusinessGlossary.invalid();
            return new Embeddings(model, result);
        });
    }
    public List<GlossaryDocument.Hit> search(PoolSession session, GlossaryDocument.Source document, Embeddings index, String text) {
        String question = BusinessGlossary.text(text, 500, true);
        return query(session, false, () -> {
            if (!models.modelAvailable(index.model().owner(), index.model().name())) throw AiAssistant.stale();
            double[] vector = vectors.embed(index.model(), question);
            var hits = new ArrayList<GlossaryDocument.Hit>();
            for (int i = 0; i < document.chunks().size(); i++) hits.add(new GlossaryDocument.Hit(document.chunks().get(i), GlossaryDocument.cosine(vector, index.values().get(i))));
            return hits.stream().sorted(Comparator.comparingDouble(GlossaryDocument.Hit::score).reversed()).limit(10).toList();
        });
    }
    public AiAssistant.Preview preview(PoolSession session, GlossaryDocument.Source document, List<String> ids, Locale locale) {
        var selected = session.metadata().assistant().selected();
        if (selected == null) throw BusinessGlossary.failure(409, "AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");
        String payload = json.writeValueAsString(Map.of("name", document.name(), "sha256", document.hash(), "totalChunks", document.chunks().size(), "chunks", GlossaryDocument.select(document, ids)));
        String language = UiMessages.supported(locale).getLanguage();
        if (GlossaryDocument.prompt(language, payload).length() > AiAssistant.MAX_SOURCE) throw BusinessGlossary.failure(413, "전송 한도를 초과합니다. 원문 조각 선택을 줄여 주세요. 자동으로 자르지 않습니다.");
        var profile = query(session, false, () -> assistant.profile(selected));
        return session.metadata().assistant().prepare(session.metadata().info().username(), profile, document.id(), payload, false, language, Instant.now(), PURPOSE);
    }
    public GlossaryDocument.Analysis analyze(PoolSession session, GlossaryDocument.Source document, String token, boolean consent) {
        var state = session.metadata().assistant();
        var draft = state.consume(token, consent, session.metadata().info().username(), Instant.now(), PURPOSE);
        try {
            if (!draft.preview().reference().equals(document.id())) throw AiAssistant.stale();
            return query(session, true, () -> {
                var before = draft.preview().profile();
                if (!before.equals(assistant.profile(before.selection()))) throw AiAssistant.stale();
                String result = assistant.explain(profiles.packageOwner(before.selection().owner()), before.selection().name(), GlossaryDocument.prompt(draft.language(), draft.preview().source()));
                var payload = json.readTree(draft.preview().source());
                List<GlossaryDocument.Chunk> sent = json.readerForListOf(GlossaryDocument.Chunk.class).readValue(payload.get("chunks"));
                return GlossaryDocument.validate(result, sent, json);
            });
        } finally { state.finish(); }
    }
}
