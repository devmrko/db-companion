package com.dbcompanion.model;

public record AiProfileAttribute(String name, String value) {
    /** Display-only row; never add a synthetic attribute to database snapshots. */
    public static java.util.List<AiProfileAttribute> withTokenLimit(java.util.List<AiProfileAttribute> attributes){
        if(attributes.stream().anyMatch(a->"max_tokens".equals(a.name())))return java.util.List.copyOf(attributes);
        var result=new java.util.ArrayList<>(attributes);result.add(new AiProfileAttribute("max_tokens",null));
        result.sort(java.util.Comparator.comparing(AiProfileAttribute::name));return java.util.List.copyOf(result);
    }
}
