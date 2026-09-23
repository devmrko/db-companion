package com.dbcompanion.common.db;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileEdit.Target;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class ProfileEditPolicy {
    public static final int MAX_BYTES = 32767;
    public static final Set<String> BOOLEANS=Set.of("comments","annotations","conversation","enforce_object_list",
            "constraints","case_sensitive_values","enable_custom_source_uri");
    private ProfileEditPolicy() {}
    private static MetadataEditException invalid(String message) {
        return new MetadataEditException(400, "Invalid profile edit", message);
    }
    public static void scope(String login, String selected, Target target) {
        if (target == null || target.schema() == null || !target.schema().equals(selected))
            throw new MetadataEditException(409, "Selected schema changed", UiMessages.text("ui.dde600c5989c", "스키마가 변경되었습니다. 상세 화면을 다시 열어 주세요."));
        if (!Objects.equals(login, selected))
            throw new MetadataEditException(403, "Profile owner required", UiMessages.text("ui.6272fd6c22e2", "프로필 소유자 계정으로 로그인해야 편집할 수 있습니다."));
        identifier(target.schema()); identifier(target.profile()); identifier(target.attribute());
        if (!editableAttribute(target.attribute())) throw invalid(UiMessages.text("ui.b37de6defb53", "이 속성은 이 화면에서 편집할 수 없습니다."));
    }
    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.getBytes(StandardCharsets.UTF_8).length > 128)
            throw invalid(UiMessages.text("ui.078c2c83feff", "스키마·프로필·속성 이름을 확인해 주세요."));
        unicode(value);
    }
    public static boolean editableAttribute(String name) {
        return name != null && name.matches("[a-z][a-z0-9_]*")
                && !name.matches(".*(?:^|_)(?:password|secret|private_key|api_key|token|access_token|refresh_token|access_key|auth_token)(?:_|$).*");
    }
    public static void input(Target target, String value, JsonMapper json) {
        if (value == null || value.isBlank()) throw invalid(UiMessages.text("ui.db6144584361", "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다."));
        unicode(value);
        if (value.indexOf('\0') >= 0) throw invalid(UiMessages.text("ui.8c2eea79f90f", "내용에 NUL 문자를 사용할 수 없습니다."));
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid(UiMessages.text("ui.2c41f4ca8f42", "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다."));
        if (BOOLEANS.contains(target.attribute()) && !Set.of("true","false").contains(value.toLowerCase(Locale.ROOT)))
            throw invalid(UiMessages.text("ui.964b402bc714", "true 또는 false를 선택해 주세요."));
        try {
            switch (target.attribute()) {
                case "seed" -> { if (!value.matches("[+-]?[0-9]+")) throw new NumberFormatException(); Long.parseLong(value); }
                case "max_tokens" -> { if (!value.matches("[+]?[0-9]+") || new java.math.BigInteger(value).signum()<=0) throw new NumberFormatException(); }
                case "temperature" -> { if (!value.matches("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?") || new BigDecimal(value).signum()<0) throw new NumberFormatException(); }
                default -> { }
            }
        } catch (NumberFormatException ex) { throw invalid(UiMessages.text("ui.15f8a40b169f", "숫자 형식과 허용 범위를 확인해 주세요.")); }
        if ("object_list".equals(target.attribute())) {
            boolean array;
            try { array = json.readTree(value).isArray(); }
            catch (RuntimeException ex) { throw invalid(UiMessages.text("ui.ddcee732ecb8", "object_list는 올바른 JSON 배열로 입력해 주세요.")); }
            if (!array) throw invalid(UiMessages.text("ui.69ae71398505", "object_list는 JSON 배열로 입력해 주세요."));
        }
    }
    private static void unicode(String value) {
        for (int i=0; i<value.length(); i++) {
            char ch=value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));
            } else if (Character.isLowSurrogate(ch)) throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));
        }
    }
    public static String value(Map<String,Object> snapshot, Target target) {
        if (!Boolean.TRUE.equals(snapshot.get("exists")))
            throw new MetadataEditException(404, "Profile not found", UiMessages.text("ui.827fdc31d3d2", "프로필이 더 이상 존재하지 않습니다."));
        if (!(snapshot.get("attributes") instanceof Map<?,?> attributes) || !attributes.containsKey(target.attribute()))
            throw new MetadataEditException(409, "Attribute not found", UiMessages.text("ui.7ef8d5ba9f46", "속성이 더 이상 존재하지 않습니다. 상세 화면을 다시 열어 주세요."));
        Object value=attributes.get(target.attribute());
        if (value != null && !(value instanceof String)) throw new IllegalStateException("Unexpected profile attribute type");
        return (String) value;
    }
    public static String version(Target target, Map<String,Object> snapshot, JsonMapper json) {
        try {
            var normalized=new TreeMap<String,Object>(snapshot);
            for (String key:List.of("profile","attributes"))
                if (snapshot.get(key) instanceof Map<?,?> values) {
                    var fields=new TreeMap<String,Object>(); values.forEach((k,v)->fields.put(k.toString(),v)); normalized.put(key,fields);
                }
            String text=json.writeValueAsString(List.of(target.schema(),target.profile(),target.attribute(),normalized));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static void verifyVersion(Target target, Map<String,Object> snapshot, String expected, JsonMapper json) {
        if (expected == null || !expected.equals(version(target,snapshot,json)))
            throw new MetadataEditException(409, "Profile changed", UiMessages.text("ui.72fcb9606fa7", "편집 중 프로필이 변경되었습니다. 입력을 복사한 뒤 최신 값을 다시 불러와 주세요."));
    }
    public static boolean equivalent(String attribute, String desired, String actual, JsonMapper json) {
        if (Objects.equals(desired,actual)) return true;
        if (desired==null || actual==null) return false;
        try {
            if ("object_list".equals(attribute)) return json.readTree(desired).equals(json.readTree(actual));
            if (Set.of("temperature","max_tokens","seed","top_p","frequency_penalty","presence_penalty").contains(attribute))
                return new BigDecimal(desired.strip()).compareTo(new BigDecimal(actual.strip()))==0;
            if (BOOLEANS.contains(attribute))
                return Set.of("true","false").contains(desired.strip().toLowerCase(Locale.ROOT)) && desired.strip().equalsIgnoreCase(actual.strip());
        } catch (RuntimeException ignored) { return false; }
        return false;
    }
    public static boolean readbackMatches(Target target, Map<String,Object> before, Map<String,Object> after, String desired, JsonMapper json) {
        if (!Boolean.TRUE.equals(after.get("exists"))) return false;
        try {
            if (!equivalent(target.attribute(),desired,value(after,target),json)) return false;
            for (String key:List.of("profile","attributes")) {
                if (!(before.get(key) instanceof Map<?,?> a) || !(after.get(key) instanceof Map<?,?> b)) return false;
                var left=new HashMap<>(a); var right=new HashMap<>(b);
                String excluded=key.equals("profile")?"MODIFIED":target.attribute();
                left.remove(excluded);right.remove(excluded);
                if (!left.equals(right)) return false;
            }
            return true;
        } catch (MetadataEditException ex) { return false; }
    }
    public static String sql(String owner) {
        return "DECLARE v_profile VARCHAR2(128) := ?; v_attribute VARCHAR2(128) := ?; v_value VARCHAR2(32767) := ?; BEGIN "
                + ProfileHistorySql.object(owner,"DBMS_CLOUD_AI")
                + ".SET_ATTRIBUTE(profile_name => v_profile, attribute_name => v_attribute, attribute_value => v_value); END;";
    }
}
