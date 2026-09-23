package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only vector explorer contracts. No profile-specific column names. */
public final class VectorSearch {
    private VectorSearch() {}
    public static final int PREVIEW_LIMIT = 500, DETAIL_LIMIT = 100_000;
    public static final Set<String> METRICS = Set.of("COSINE", "EUCLIDEAN", "EUCLIDEAN_SQUARED", "DOT", "MANHATTAN", "HAMMING", "JACCARD");
    public record Table(String name, String description, List<String> vectors) {
        public Table { vectors = List.copyOf(vectors); }
        public String vectorLabel() { return String.join(", ", vectors); }
    }
    public record Column(String name, String type, String description) {
        public boolean vector() { return "VECTOR".equals(type); }
        public boolean text() { return Set.of("CHAR","VARCHAR2","NCHAR","NVARCHAR2","CLOB","NCLOB","JSON").contains(type); }
        public boolean supported() {
            return text() || vector() || Set.of("NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE","DATE","RAW","ROWID","UROWID","BOOLEAN").contains(type)
                    || type.startsWith("TIMESTAMP") || type.startsWith("INTERVAL");
        }
    }
    public record Model(String owner, String name) { public String label() { return owner + "." + name; } }
    public record Options(List<Model> models, List<String> credentials, String modelError, String credentialError) {}
    public record Selection(String schema, String table, String vector, String content) {
        public Selection { name(schema); name(table); name(vector); if (content == null) content = ""; if (!content.isEmpty()) name(content); }
    }
    public record Search(Selection selection, String text, String provider, String modelOwner, String model,
                         String credential, String region, String inputType, String metric, int k, boolean externalConsent) {
        public Search {
            Objects.requireNonNull(selection, "Selection required");
            if (text == null || text.isBlank() || text.indexOf('\0') >= 0 || text.getBytes(StandardCharsets.UTF_8).length > 4000)
                throw invalid(UiMessages.text("ui.3b1206b60d82", "검색어는 UTF-8 기준 4,000바이트 이내로 입력해 주세요."));
            if (k < 1 || k > 100) throw invalid(UiMessages.text("ui.7516f84cee1e", "K는 1~100으로 입력해 주세요."));
            if (!METRICS.contains(Objects.toString(metric,""))) throw invalid(UiMessages.text("ui.58600e1848e7", "거리 계산 방식을 선택해 주세요."));
            if (!Set.of("database","ocigenai","cohere","openai").contains(Objects.toString(provider,""))) throw invalid(UiMessages.text("ui.4de4330db3b4", "임베딩 방식을 선택해 주세요."));
            modelOwner = Objects.toString(modelOwner, ""); credential = Objects.toString(credential, "");
            region = Objects.toString(region, ""); inputType = Objects.toString(inputType, "");
            if ("database".equals(provider)) { name(modelOwner); name(model); }
            else {
                if (!externalConsent) throw invalid(UiMessages.text("ui.206340f59aad", "외부 임베딩 전송·비용 확인이 필요합니다."));
                name(credential);
                if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) throw invalid(UiMessages.text("ui.8485f065ef62", "임베딩 모델 이름을 확인해 주세요."));
                if ("ocigenai".equals(provider) && !region.matches("[a-z]{2}-[a-z]+-[1-9][0-9]?")) throw invalid(UiMessages.text("ui.1b3dd86a1df6", "OCI 리전 이름을 확인해 주세요."));
                if (!inputType.isEmpty() && (!Set.of("ocigenai","cohere").contains(provider)
                        || !Set.of("search_query","search_document","classification","clustering").contains(inputType)))
                    throw invalid(UiMessages.text("ui.6a9d42953a7a", "선택한 제공자의 input_type을 확인해 주세요."));
            }
        }
        public boolean external() { return !"database".equals(provider); }
        public String executionSchema(String login) {
            return external() ? name(login) : selection.schema();
        }
        public String endpoint() {
            return switch (provider) {
                case "ocigenai" -> "https://inference.generativeai." + region + ".oci.oraclecloud.com/20231130/actions/embedText";
                case "cohere" -> "https://api.cohere.ai/v1/embed";
                case "openai" -> "https://api.openai.com/v1/embeddings";
                default -> "";
            };
        }
        public Map<String,Object> externalParams() {
            if (!external()) throw invalid(UiMessages.text("ui.0eb511f65267", "외부 임베딩 옵션이 아닙니다."));
            var params = new LinkedHashMap<String,Object>();
            params.put("provider",provider); params.put("model",model);
            // The service binds CURRENT_SCHEMA to the login and validates USER_CREDENTIALS.
            // DBMS_VECTOR on this ADB rejects owner-prefixed names; use a single name.
            params.put("credential_name",credentialPart(credential));
            params.put("url",endpoint()); params.put("transfer_timeout",20);
            if (!inputType.isEmpty()) {
                // Additional REST parameters must use the selected provider's wire format.
                if ("ocigenai".equals(provider)) params.put("inputType",inputType.toUpperCase(Locale.ROOT));
                else params.put("input_type",inputType);
            }
            return Collections.unmodifiableMap(params);
        }
    }
    public record Row(String id, String preview, boolean previewTruncated, Integer dimensions, String format, Double distance) {}
    public record Rows(List<Row> items, int page, boolean hasNext, boolean search) {
        public Rows { items = List.copyOf(items); }
    }
    public record Value(String name, String type, String value, boolean truncated, boolean supported) {}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status, String message) { super(message); this.status=status; }
        public int status() { return status; }
    }
    public static Failure invalid(String message) { return new Failure(400,message); }
    public static String name(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.getBytes(StandardCharsets.UTF_8).length > 128)
            throw invalid(UiMessages.text("ui.2fe5b0bf749b", "스키마·테이블·컬럼·모델 이름을 확인해 주세요."));
        return value;
    }
    public static String quote(String value) { return "\"" + name(value).replace("\"","\"\"") + "\""; }
    private static String credentialPart(String value) {
        name(value);
        return value.matches("[A-Z][A-Z0-9_$#]*") ? value : quote(value);
    }
    public static String rowId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9+/]{18}")) throw invalid(UiMessages.text("ui.59e58ddc5580", "행 조회 위치를 확인해 주세요."));
        return value;
    }
    public static int page(int value) { if(value < 1 || value > 1000) throw invalid(UiMessages.text("ui.c22886e55dca", "페이지는 1~1,000까지 조회할 수 있습니다.")); return value; }
    public static void scope(String selected, String requested) {
        if (!Objects.equals(selected,requested)) throw new Failure(409,UiMessages.text("ui.b05af1b875ea", "스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
    }
    public static void columns(Selection selection, List<Column> columns) {
        if(columns.stream().noneMatch(c -> c.vector() && c.name().equals(selection.vector()))) throw invalid(UiMessages.text("ui.b0c6f1c620fb", "조회 가능한 벡터 컬럼을 선택해 주세요."));
        if(!selection.content().isEmpty() && columns.stream().noneMatch(c -> c.supported() && !c.vector() && c.name().equals(selection.content())))
            throw invalid(UiMessages.text("ui.9a88fbe97222", "조회 가능한 내용 컬럼을 선택해 주세요."));
    }
    public static String clip(String text, int limit) {
        if(text == null || text.length() <= limit) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit-1)) ? limit-1 : limit);
    }
}
