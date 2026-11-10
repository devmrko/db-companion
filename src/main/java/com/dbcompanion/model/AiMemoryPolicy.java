package com.dbcompanion.model;

import com.dbcompanion.common.exception.MetadataEditException;
import java.util.Set;
import java.util.Map;

/** Owner-scoped native store/policy configuration. No secrets are accepted. */
public final class AiMemoryPolicy {
    private AiMemoryPolicy() {}
    public static final Set<String> LINKS = Set.of("memory_store_name", "guardrail");
    public record Create(String schema, String kind, String name, String profile,
                         String description, Integer matchLimit, Integer refreshRate,
                         String mode, boolean pii, boolean injection, boolean moderation, boolean confirmed) {}
    public record Capability(String version, String owner, boolean memory, boolean guardrail,
                             String guardrailView, String reason) {}
    public record Resource(String name, String profile, String status, String description) {}
    public record Result(boolean verified, String message) {}
    public record Observation(String id, String conversationId, String created, String promptStatus,
                              String responseStatus, String prompt, String response) {}
    public record Conversation(String id, String title, String modified, long prompts) {}
    public record StoreDetails(String id, String name, String profile, String description, Map<String,String> attributes) {}
    public record Extract(String schema, String profile, String store, String conversationId, boolean confirmed) {}
    public static void validateExtraction(Extract value) {
        if (value == null || !value.confirmed() || value.conversationId() == null
                || !value.conversationId().matches("[A-Za-z0-9-]{1,128}"))
            throw error(400, "대화 ID와 추출 승인을 확인하세요.");
        name(value.profile()); name(value.store());
    }
    public static MetadataEditException error(int status, String message) {
        return new MetadataEditException(status, "AI memory/policy", message);
    }
    public static void name(String name) {
        if (name == null || !name.matches("[A-Z][A-Z0-9_$#]{0,124}"))
            throw error(400, "이름은 대문자 영문으로 시작하는 125자 이내의 DB 이름이어야 합니다.");
    }
    public static void validate(Create value) {
        if (value == null || !value.confirmed()) throw error(400, "생성 내용과 외부 서비스 호출 가능성을 확인해 주세요.");
        if (!Set.of("memory", "guardrail").contains(java.util.Objects.toString(value.kind(), "")))
            throw error(400, "생성 유형을 확인해 주세요.");
        name(value.name()); name(value.profile());
        if (value.description() == null || value.description().length() > 1000 || value.description().indexOf('\0') >= 0)
            throw error(400, "설명은 1,000자 이내로 입력해 주세요.");
        if (value.kind().equals("memory")) {
            if (value.matchLimit() == null || value.matchLimit() < 1 || value.matchLimit() > 99
                    || value.refreshRate() != null && (value.refreshRate() < 1 || value.refreshRate() > 999))
                throw error(400, "match_limit은 1~99, refresh_rate는 미지정 또는 1~999입니다.");
        } else if (!Set.of("detect", "enforce").contains(java.util.Objects.toString(value.mode(), ""))
                || !(value.pii() || value.injection() || value.moderation()))
            throw error(400, "detect/enforce 모드와 하나 이상의 검사 항목을 선택해 주세요.");
    }
}
