package com.dbcompanion.model;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded source evidence, not executable code or an authoritative business definition. */
public final class FunctionExplanation {
    private FunctionExplanation() {}
    public static final int MAX_CONTEXT=4000;
    private static final JsonMapper JSON=new JsonMapper();
    public record Line(int line,String text) {}
    public record Section(String type,List<Line> lines) {}
    public record Evidence(String reference,List<FunctionCatalog.Argument> arguments,List<Section> sections,String businessContext) {}
    public static String context(String value){
        String text=Objects.toString(value,"").strip();
        if(text.length()>MAX_CONTEXT||text.indexOf(0)>=0)throw new AiAssistant.Failure(400,"assistant.contextInvalid","업무 설명은 4,000자 이내로 입력해 주세요. 제어 문자 NUL은 사용할 수 없습니다.");
        return text;
    }
    public static String source(FunctionCatalog.Detail detail,String businessContext){
        var definition=detail.definition();AiAssistant.source(definition); // Reject wrapped, hidden and oversized source.
        var sections=definition.sections().stream().map(section->{
            var rows=section.text().split("\\R",-1);var lines=new ArrayList<Line>();
            for(int i=0;i<rows.length;i++)lines.add(new Line(i+1,rows[i]));
            return new Section(section.type(),List.copyOf(lines));
        }).toList();
        String result=JSON.writeValueAsString(new Evidence(FunctionCatalog.reference(definition.owner(),definition.object(),definition.member()),detail.arguments(),sections,context(businessContext)));
        if(result.length()>AiAssistant.MAX_SOURCE)throw new AiAssistant.Failure(413,"assistant.evidenceTooLong","코드·인자·업무 설명이 전송 한도 64,000자를 초과합니다. 일부만 전송하지 않습니다.");
        return result;
    }
    public static String businessContext(String source){return JSON.readValue(source,Evidence.class).businessContext();}
}
