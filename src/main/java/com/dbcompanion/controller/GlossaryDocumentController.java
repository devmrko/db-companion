package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/business-glossary/document")
public class GlossaryDocumentController {
    private static final String KEY = GlossaryDocumentController.class.getName();
    private static final class Held {
        private final PoolSession owner;
        private final GlossaryDocument.Source source;
        private final GlossaryDocument.Index index;
        private GlossaryDocumentService.Embeddings vectors;
        private GlossaryDocument.Analysis analysis;
        private GlossaryTransfer.Preview transfer;
        private String previewToken;
        Held(PoolSession owner, GlossaryDocument.Source source) { this.owner = owner; this.source = source; index = new GlossaryDocument.Index(source); }
    }
    private final GlossaryDocumentReader reader;
    private final GlossaryDocumentService service;
    private final GlossaryTransferService transfer;
    private final JsonMapper json;
    public GlossaryDocumentController(GlossaryDocumentReader reader, GlossaryDocumentService service, GlossaryTransferService transfer, JsonMapper json) {
        this.reader = reader; this.service = service; this.transfer = transfer; this.json = json;
    }
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return run(request, session -> {
            discard(request, session);
            if (file.getSize() > GlossaryDocumentReader.MAX_BYTES) throw BusinessGlossary.failure(413, "문서 파일 한도는 4 MB입니다.");
            try {
                var source = GlossaryDocument.source(file.getOriginalFilename(), reader.read(file.getOriginalFilename(), file.getBytes()));
                request.getSession().setAttribute(KEY, new Held(session, source)); return source;
            } catch (IOException ex) { throw BusinessGlossary.failure(422, "문서를 읽지 못했습니다."); }
        });
    }
    @PostMapping("/clear")
    public ResponseEntity<?> clear(HttpServletRequest request) { return run(request, session -> { discard(request, session); return Map.of("cleared", true); }); }
    @GetMapping("/models")
    public ResponseEntity<?> models(HttpServletRequest request) { return run(request, service::models); }
    public record IndexRequest(String id, VectorSearch.Model model, boolean consent) {}
    @PostMapping("/index")
    public ResponseEntity<?> index(@RequestBody IndexRequest value, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id());
            if (!value.consent()) throw BusinessGlossary.failure(400, "로컬 임베딩의 DB 자원 사용을 확인해 주세요.");
            held.vectors = null; held.vectors = service.index(session, held.source, value.model());
            return Map.of("chunks", held.vectors.values().size(), "model", held.vectors.model());
        });
    }
    public record Search(String id, String question, boolean semantic) {}
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Search value, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id());
            if (value.semantic() && held.vectors == null) throw BusinessGlossary.failure(409, "먼저 로컬 임베딩 색인을 만드세요.");
            return value.semantic() ? service.search(session, held.source, held.vectors, value.question()) : held.index.search(value.question());
        });
    }
    public record Prepare(String id, List<String> chunks) {}
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Prepare value, Locale locale, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id());
            var preview = service.preview(session, held.source, value.chunks(), locale); held.previewToken = preview.token(); return preview;
        });
    }
    public record Generate(String id, String token, boolean consent) {}
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Generate value, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id()); held.analysis = null; held.transfer = null;
            held.analysis = service.analyze(session, held.source, value.token(), value.consent()); return held.analysis;
        });
    }
    public record Review(String id, List<Integer> rows, boolean enabled) {}
    @PostMapping("/review")
    public ResponseEntity<?> review(@RequestBody Review value, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id()); held.transfer = null;
            if (held.analysis == null) throw AiAssistant.stale();
            var document = GlossaryDocument.document(held.source, held.analysis, value.rows(), value.enabled());
            held.transfer = transfer.preview(session, json.writeValueAsString(document)); return held.transfer;
        });
    }
    public record Apply(String id, GlossaryTransfer.Apply selection) {}
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody Apply value, HttpServletRequest request) {
        return run(request, session -> {
            var held = held(request, session, value.id()); var preview = held.transfer; held.transfer = null;
            return Map.of("saved", transfer.apply(session, preview, value.selection()));
        });
    }
    private Held held(HttpServletRequest request, PoolSession owner, String id) {
        Object value = request.getSession().getAttribute(KEY);
        if (!(value instanceof Held held) || held.owner != owner) throw AiAssistant.stale();
        try { GlossaryDocument.require(held.source, id); } catch (RuntimeException ex) { discard(request, owner); throw ex; }
        return held;
    }
    private void discard(HttpServletRequest request, PoolSession owner) {
        Object value = request.getSession().getAttribute(KEY);
        if (value instanceof Held held && held.owner == owner) owner.metadata().assistant().discard(held.previewToken);
        request.getSession().removeAttribute(KEY);
    }
    private ResponseEntity<?> run(HttpServletRequest request, Function<PoolSession, Object> action) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return error(401, "로그인 세션이 만료되었습니다.");
        synchronized (session) {
            try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(action.apply(session)); }
            catch (AiAssistant.Failure ex) { return error(ex.status(), ex.getMessage()); }
            catch (IllegalArgumentException ex) { return error(400, "문서·선택 항목을 확인해 주세요."); }
            catch (RuntimeException ex) { return error(503, "요청을 완료하지 못했습니다. AI 호출 시 사용량이 발생했을 수 있습니다. 자동 재시도하지 않았습니다. 저장 요청이었다면 사전을 새로고침해 확인하세요."); }
        }
    }
    private ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", message)); }
}
