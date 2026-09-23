package com.dbcompanion.common.db;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.TeamEdit.Target;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Team-only input and optimistic concurrency rules. No inferred task or agent changes. */
public final class TeamEditPolicy {
    public static final int MAX_BYTES = 32767;
    public static final Set<String> EDITABLE = Set.of("agents", "process", "supervisor_agent", "long_term_memory_length");
    private TeamEditPolicy() {}
    private static MetadataEditException invalid(String message) {
        return new MetadataEditException(400, "Invalid team edit", message);
    }
    public static void scope(String login, String selected, Target target) {
        if (target == null || target.schema() == null || !target.schema().equals(selected))
            throw new MetadataEditException(409, "Selected schema changed", UiMessages.text("ui.5f72c23cbab8", "스키마가 변경되었습니다. Team 상세를 다시 열어 주세요."));
        if (!Objects.equals(login, selected))
            throw new MetadataEditException(403, "Team owner required", UiMessages.text("ui.2e37cd197d58", "Team 소유자 계정으로 로그인해야 편집할 수 있습니다."));
        identifier(target.schema()); identifier(target.team());
        if (!EDITABLE.contains(Objects.toString(target.attribute(), ""))) throw invalid(UiMessages.text("ui.6feceb03f35f", "이 속성은 편집할 수 없습니다."));
    }
    public static void identifier(String name) {
        text(name);
        if (name.isBlank() || name.getBytes(StandardCharsets.UTF_8).length > 128)
            throw invalid(UiMessages.text("ui.9ab91d4f7fd4", "이름은 UTF-8 기준 128바이트 이내로 입력해 주세요."));
    }
    private static void text(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw invalid(UiMessages.text("ui.aa79720997f4", "올바른 값을 입력해 주세요."));
        for (int i=0; i<value.length(); i++) {
            char ch=value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));
            } else if (Character.isLowSurrogate(ch)) throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));
        }
    }
    public static void input(Target target, String value, JsonMapper json) {
        text(value);
        if (value.isBlank()) throw invalid(UiMessages.text("ui.db6144584361", "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다."));
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid(UiMessages.text("ui.2c41f4ca8f42", "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다."));
        switch (target.attribute()) {
            case "agents" -> assignments(value, json);
            case "process" -> {
                if (!"sequential".equals(value)) throw invalid(UiMessages.text("ui.a58741280753", "process는 sequential만 지원합니다."));
            }
            case "supervisor_agent" -> identifier(referenceName(value));
            case "long_term_memory_length" -> {
                if (!value.matches("[0-9]+") || new java.math.BigInteger(value).signum() <= 0)
                    throw invalid(UiMessages.text("ui.38aa6c4bc45a", "long_term_memory_length는 1 이상의 정수로 입력해 주세요."));
            }
            default -> throw invalid(UiMessages.text("ui.6feceb03f35f", "이 속성은 편집할 수 없습니다."));
        }
    }
    public static List<Assignment> assignments(String value, JsonMapper json) {
        try {
            var root=json.readTree(value);
            if (root == null || !root.isArray() || root.isEmpty()) throw invalid(UiMessages.text("ui.2ff186673083", "Agent·Task 연결을 한 개 이상 지정해 주세요."));
            var pairs=new ArrayList<Assignment>();
            var seen=new HashSet<Assignment>();
            for (var node:root) {
                if (!node.isObject() || !node.has("name") || !node.has("task")
                        || !node.get("name").isString() || !node.get("task").isString())
                    throw invalid(UiMessages.text("ui.e10d53a5b287", "agents는 name과 task를 가진 JSON 객체 배열이어야 합니다."));
                String agent=referenceName(node.get("name").stringValue()), task=referenceName(node.get("task").stringValue());
                identifier(agent); identifier(task);
                var pair=new Assignment(agent,task);
                if (!seen.add(pair)) throw invalid(UiMessages.text("ui.f2be01325fbc", "같은 Agent·Task 연결이 중복되어 있습니다."));
                pairs.add(pair);
            }
            return List.copyOf(pairs);
        } catch (MetadataEditException ex) { throw ex; }
        catch (RuntimeException ex) { throw invalid(UiMessages.text("ui.0b2a0a296ea5", "agents는 올바른 JSON 배열로 입력해 주세요.")); }
    }
    public static String referenceName(String value) {
        if (value == null) return null;
        if (value.startsWith("\"") && value.endsWith("\"") && value.length()>2)
            return value.substring(1,value.length()-1).replace("\"\"", "\"");
        return value.toUpperCase(Locale.ROOT);
    }
    public static String value(Component team, String attribute) {
        if (team == null || team.info() == null)
            throw new MetadataEditException(404, "Team not found", UiMessages.text("ui.4a45b4224a1d", "Team이 더 이상 존재하지 않습니다."));
        return team.attributes().stream().filter(a -> a.name().equals(attribute)).findFirst()
                .orElseThrow(() -> new MetadataEditException(409, "Team attribute missing", UiMessages.text("ui.ad8ad211b20d", "속성이 변경되었습니다. Team 상세를 다시 열어 주세요."))).value();
    }
    public static String version(Target target, Component team, JsonMapper json) {
        var attributes=team.attributes().stream().sorted(Comparator.comparing(Attribute::name)).toList();
        try {
            String content=json.writeValueAsString(List.of(target,team.info(),attributes));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static void verifyVersion(Target target, Component team, String version, JsonMapper json) {
        if (version == null || !version.equals(version(target,team,json)))
            throw new MetadataEditException(409, "Team changed", UiMessages.text("ui.297bd389f9d8", "편집 중 Team이 변경되었습니다. 입력을 복사한 뒤 최신 값을 다시 불러와 주세요."));
    }
    public static boolean equivalent(String attribute, String desired, String actual, JsonMapper json) {
        if (Objects.equals(desired,actual)) return true;
        if (desired==null || actual==null) return false;
        try {
            return switch (attribute) {
                case "agents" -> json.readTree(desired).equals(json.readTree(actual));
                case "long_term_memory_length" -> new java.math.BigDecimal(desired).compareTo(new java.math.BigDecimal(actual))==0;
                case "supervisor_agent" -> referenceName(desired).equals(referenceName(actual));
                default -> false;
            };
        } catch (RuntimeException ex) { return false; }
    }
    public static boolean readbackMatches(Target target, Component before, Component after, String desired, JsonMapper json) {
        if (after==null || after.info()==null) return false;
        try {
            if (!equivalent(target.attribute(),desired,value(after,target.attribute()),json)) return false;
            var a=before.info(); var b=after.info();
            if (!Objects.equals(a.id(),b.id()) || !Objects.equals(a.name(),b.name())
                    || !Objects.equals(a.description(),b.description()) || !Objects.equals(a.status(),b.status())
                    || !Objects.equals(a.created(),b.created())) return false;
            return otherValues(before,target.attribute()).equals(otherValues(after,target.attribute()));
        } catch (MetadataEditException ex) { return false; }
    }
    private static Map<String,String> otherValues(Component team, String attribute) {
        var result=new TreeMap<String,String>();
        team.attributes().stream().filter(a -> !a.name().equals(attribute)).forEach(a -> result.put(a.name(),a.value()));
        return result;
    }
}
