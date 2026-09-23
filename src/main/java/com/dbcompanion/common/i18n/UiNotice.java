package com.dbcompanion.common.i18n;

import java.util.List;

/** Immutable display recipe for session-cached notices. Raw arguments are never translated. */
public record UiNotice(String key, String value, List<UiNotice> parts) {
    public UiNotice { parts=List.copyOf(parts); }
    public static UiNotice raw(String value) { return new UiNotice(null,value==null?"":value,List.of()); }
    public static UiNotice message(String key,String fallback) { return new UiNotice(key,fallback,List.of()); }
    public static UiNotice join(UiNotice separator,List<UiNotice> items) {
        var result=new java.util.ArrayList<UiNotice>();
        for(var item:items) { if(!result.isEmpty())result.add(separator);result.add(item); }
        return concat(result.toArray(UiNotice[]::new));
    }
    public static UiNotice concat(UiNotice... parts) { return new UiNotice(null,"",List.of(parts)); }
    public String render() {
        if(key!=null)return UiMessages.text(key,value);
        return value+parts.stream().map(UiNotice::render).collect(java.util.stream.Collectors.joining());
    }
}
