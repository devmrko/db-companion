package com.dbcompanion.model;

public record AiProfileAttribute(String name, String value) {
    public static final java.util.List<String> CONFIGURABLE = java.util.List.of(
            "provider", "model", "credential_name", "object_list", "comments", "annotations",
            "constraints", "enforce_object_list", "conversation", "max_tokens", "temperature",
            "additional_instructions", "region", "oci_compartment_id", "oci_endpoint_id",
            "azure_resource_name", "azure_deployment_name", "provider_endpoint");
    /** Missing settings are UI rows only; never copy defaults from another profile. */
    public static java.util.List<AiProfileAttribute> withConfiguration(java.util.List<AiProfileAttribute> attributes) {
        var result = new java.util.ArrayList<>(attributes);
        var names = attributes.stream().map(AiProfileAttribute::name).collect(java.util.stream.Collectors.toSet());
        String provider = attributes.stream().filter(a -> "provider".equals(a.name())).map(a -> java.util.Objects.toString(a.value(), "")).findFirst().orElse("");
        for (String name : CONFIGURABLE) {
            if (name.startsWith("oci_") && !provider.equals("oci")) continue;
            if (name.startsWith("azure_") && !provider.equals("azure")) continue;
            if (!names.contains(name)) result.add(new AiProfileAttribute(name, null));
        }
        result.sort(java.util.Comparator.comparing(AiProfileAttribute::name));
        return java.util.List.copyOf(result);
    }
    /** Display-only row; never add a synthetic attribute to database snapshots. */
    public static java.util.List<AiProfileAttribute> withTokenLimit(java.util.List<AiProfileAttribute> attributes){
        if(attributes.stream().anyMatch(a->"max_tokens".equals(a.name())))return java.util.List.copyOf(attributes);
        var result=new java.util.ArrayList<>(attributes);result.add(new AiProfileAttribute("max_tokens",null));
        result.sort(java.util.Comparator.comparing(AiProfileAttribute::name));return java.util.List.copyOf(result);
    }
}
