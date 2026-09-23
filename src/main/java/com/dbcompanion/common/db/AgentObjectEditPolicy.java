package com.dbcompanion.common.db;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.AgentObjectEdit.*;
import com.dbcompanion.service.SqlObjectName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class AgentObjectEditPolicy {
    public static final int MAX_BYTES=32767;
    public static final Map<Kind,Set<String>> EDITABLE=Map.of(
            Kind.AGENT,Set.of("role","profile_name","enable_human_tool","tools","short_term_memory_length"),
            Kind.TASK,Set.of("instruction","tools","input","enable_human_tool"),
            Kind.TOOL,Set.of("instruction","function","tool_type","tool_params","tool_inputs"));
    public static final Set<String> TOOL_TYPES=Set.of("SQL","RAG","WEBSEARCH","NOTIFICATION");
    private AgentObjectEditPolicy() {}
    public static MetadataEditException invalid(String message) { return new MetadataEditException(400,"Invalid object edit",message); }
    public static void scope(String login,String selected,HistoryTarget target) {
        if(target==null||target.schema()==null||!target.schema().equals(selected))
            throw new MetadataEditException(409,"Selected schema changed",UiMessages.text("ui.dde600c5989c", "스키마가 변경되었습니다. 상세 화면을 다시 열어 주세요."));
        if(!Objects.equals(login,selected))throw new MetadataEditException(403,"Object owner required",UiMessages.text("ui.9cad11a801c1", "객체 소유자 계정으로 로그인해야 편집할 수 있습니다."));
        kind(target.kind());TeamEditPolicy.identifier(target.schema());TeamEditPolicy.identifier(target.name());
    }
    public static void kind(Kind kind) {
        if(kind==null||!EDITABLE.containsKey(kind))throw invalid(UiMessages.text("ui.c223958cac76", "Agent, Task, Tool만 편집할 수 있습니다."));
    }
    public static void attribute(Target target) {
        kind(target.kind());
        if(!EDITABLE.get(target.kind()).contains(Objects.toString(target.attribute(),"")))throw invalid(UiMessages.text("ui.6feceb03f35f", "이 속성은 편집할 수 없습니다."));
    }
    public static void input(Target target,String value,JsonMapper json) {
        attribute(target);text(value);
        if(value.isBlank())throw invalid(UiMessages.text("ui.db6144584361", "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다."));
        switch(target.attribute()) {
            case "enable_human_tool" -> { if(!Set.of("true","false").contains(value.toLowerCase(Locale.ROOT)))throw invalid(UiMessages.text("ui.964b402bc714", "true 또는 false를 선택해 주세요.")); }
            case "short_term_memory_length" -> { if(!value.matches("[0-9]+")||new java.math.BigInteger(value).signum()<=0)throw invalid(UiMessages.text("ui.6a6788643058", "1 이상의 정수를 입력해 주세요.")); }
            case "profile_name","input" -> reference(value);
            case "tools" -> names(value,json);
            case "function" -> { try{SqlObjectName.parts(value);}catch(IllegalArgumentException ex){throw invalid(UiMessages.text("ui.f902cd622d15", "함수·프로시저 이름만 입력해 주세요. 호출문이나 DB link는 지원하지 않습니다."));} }
            case "tool_type" -> { if(!TOOL_TYPES.contains(value))throw invalid(UiMessages.text("ui.816d91b2e47a", "지원하는 Tool 유형을 선택해 주세요.")); }
            case "tool_params" -> { if(!tree(value,json).isObject())throw invalid(UiMessages.text("ui.f0bfba52f70c", "tool_params는 JSON 객체로 입력해 주세요.")); }
            case "tool_inputs" -> {
                var root=tree(value,json);if(!root.isArray())throw invalid(UiMessages.text("ui.86c2c689dd67", "tool_inputs는 JSON 객체 배열로 입력해 주세요."));
                var seen=new HashSet<String>();
                for(var node:root) {
                    if(!node.isObject()||!node.has("name")||!node.get("name").isString()||node.get("name").stringValue().isBlank())throw invalid(UiMessages.text("ui.3d2aa635e3a7", "각 입력에 name을 지정해 주세요."));
                    if(!seen.add(node.get("name").stringValue()))throw invalid(UiMessages.text("ui.3e594d8aea5e", "입력 이름이 중복되어 있습니다."));
                    if(node.has("description")&&!node.get("description").isString())throw invalid(UiMessages.text("ui.1f0c765bc39b", "입력 설명은 문자열이어야 합니다."));
                }
            }
            default -> { /* role and instruction are preserved exactly */ }
        }
    }
    public static void text(String value) {
        if(value==null||value.indexOf('\0')>=0)throw invalid(UiMessages.text("ui.aa79720997f4", "올바른 값을 입력해 주세요."));
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.isHighSurrogate(ch)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));}
            else if(Character.isLowSurrogate(ch))throw invalid(UiMessages.text("ui.9a8a24d45fd9", "올바르지 않은 Unicode 문자가 있습니다."));
        }
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid(UiMessages.text("ui.2c41f4ca8f42", "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다."));
    }
    public static String reference(String value) {
        try {
            var parts=SqlObjectName.parts(value);
            if(parts.size()!=1)throw invalid(UiMessages.text("ui.0b721fa33f4a", "현재 스키마의 객체 이름을 선택해 주세요."));
            TeamEditPolicy.identifier(parts.getFirst());return parts.getFirst();
        }catch(IllegalArgumentException ex){throw invalid(UiMessages.text("ui.360329d94389", "객체 이름을 확인해 주세요."));}
    }
    public static JsonNode tree(String value,JsonMapper json) {
        try{var result=json.readTree(value);if(result==null)throw invalid(UiMessages.text("ui.ae8c018486f2", "JSON 값을 입력해 주세요."));return result;}
        catch(MetadataEditException ex){throw ex;}catch(RuntimeException ex){throw invalid(UiMessages.text("ui.a3029d3e034d", "올바른 JSON을 입력해 주세요."));}
    }
    public static List<String> names(String value,JsonMapper json) {
        var root=tree(value,json);if(!root.isArray())throw invalid(UiMessages.text("ui.de8dfc553b49", "Tool 이름의 JSON 배열로 입력해 주세요."));
        var names=new LinkedHashSet<String>();
        for(var node:root){if(!node.isString())throw invalid(UiMessages.text("ui.4a3307fb0062", "Tool 이름은 문자열이어야 합니다."));if(!names.add(reference(node.stringValue())))throw invalid(UiMessages.text("ui.23b03cbee181", "같은 Tool이 중복되어 있습니다."));}
        return List.copyOf(names);
    }
    public static String value(Component object,String name) {
        if(object==null||object.info()==null)throw new MetadataEditException(404,"Object not found",UiMessages.text("ui.5f35ec9e62d5", "객체가 더 이상 존재하지 않습니다."));
        return object.attributes().stream().filter(a->a.name().equals(name)).findFirst()
                .orElseThrow(()->new MetadataEditException(409,"Attribute missing",UiMessages.text("ui.4da5f8ca5e05", "속성이 변경되었습니다. 상세 화면을 다시 열어 주세요."))).value();
    }
    public static void validateTaskChain(String self,String input,java.util.function.Function<String,String> nextInput) {
        var seen=new HashSet<String>();seen.add(self);String next=reference(input);
        for(int depth=0;depth<64;depth++) {
            if(!seen.add(next))throw invalid(UiMessages.text("ui.fd18274f6441", "Task input에 자기 참조나 순환 연결을 지정할 수 없습니다."));
            String following=nextInput.apply(next);if(following==null||following.isBlank())return;next=reference(following);
        }
        throw invalid(UiMessages.text("ui.dc52699d643a", "Task input 연결이 64단계를 넘습니다. 연결을 먼저 확인해 주세요."));
    }
    public static String optional(Component object,String name) {
        return object.attributes().stream().filter(a->a.name().equals(name)).map(Attribute::value).filter(Objects::nonNull).findFirst().orElse(null);
    }
    public static String version(Target target,Component object,JsonMapper json) {
        return target.kind().name()+":"+TeamEditPolicy.version(new com.dbcompanion.model.TeamEdit.Target(target.schema(),target.name(),target.attribute()),object,json);
    }
    public static void verifyVersion(Target target,Component object,String version,JsonMapper json) {
        if(version==null||!version.equals(version(target,object,json)))throw new MetadataEditException(409,"Object changed",UiMessages.text("ui.f52ab1fc764e", "편집 중 객체가 변경되었습니다. 입력을 복사한 뒤 최신 값을 다시 불러와 주세요."));
    }
    public static boolean equivalent(String attribute,String desired,String actual,JsonMapper json) {
        if(Objects.equals(desired,actual))return true;if(desired==null||actual==null)return false;
        try{return switch(attribute) {
            case "tool_params","tool_inputs" -> json.readTree(desired).equals(json.readTree(actual));
            case "tools" -> names(desired,json).equals(names(actual,json));
            case "profile_name","input" -> reference(desired).equals(reference(actual));
            case "enable_human_tool" -> Set.of("true","false").contains(desired.toLowerCase(Locale.ROOT))&&desired.equalsIgnoreCase(actual);
            case "short_term_memory_length" -> new java.math.BigDecimal(desired).compareTo(new java.math.BigDecimal(actual))==0;
            default -> false;
        };}catch(RuntimeException ex){return false;}
    }
    public static boolean readbackMatches(Target target,Component before,Component after,String desired,JsonMapper json) {
        if(after==null||after.info()==null)return false;
        try {
            if(!equivalent(target.attribute(),desired,value(after,target.attribute()),json))return false;
            var a=before.info();var b=after.info();
            if(!Objects.equals(a.id(),b.id())||!Objects.equals(a.name(),b.name())||!Objects.equals(a.description(),b.description())
                    ||!Objects.equals(a.status(),b.status())||!Objects.equals(a.created(),b.created()))return false;
            return other(before,target.attribute()).equals(other(after,target.attribute()));
        }catch(MetadataEditException ex){return false;}
    }
    private static Map<String,String> other(Component object,String attribute) {
        var map=new TreeMap<String,String>();object.attributes().stream().filter(a->!a.name().equals(attribute)).forEach(a->map.put(a.name(),a.value()));return map;
    }
}
