package com.dbcompanion.common.i18n;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** UI messages only. Never translate database values, SQL or Oracle error text. */
public final class UiMessages {
    public static final Set<String> LANGUAGES = Set.of("ko", "en", "zh-CN", "ja");
    private static final ResourceBundleMessageSource SOURCE = new ResourceBundleMessageSource();
    static {
        SOURCE.setBasename("i18n/messages");
        SOURCE.setDefaultEncoding("UTF-8");
        SOURCE.setFallbackToSystemLocale(false);
        SOURCE.setDefaultLocale(Locale.KOREAN);
    }
    private UiMessages() {}
    public static ResourceBundleMessageSource source() { return SOURCE; }
    public static Locale supported(Locale locale) {
        if (locale == null) return Locale.KOREAN;
        return switch (locale.getLanguage()) {
            case "en" -> Locale.ENGLISH;
            case "ja" -> Locale.JAPANESE;
            case "zh" -> Locale.SIMPLIFIED_CHINESE;
            default -> Locale.KOREAN;
        };
    }
    public static String text(String key, String fallback, Object... args) {
        var locale=LocaleContextHolder.getLocaleContext()==null?Locale.KOREAN:LocaleContextHolder.getLocale();
        var pattern=SOURCE.getMessage(key, null, fallback, supported(locale));
        // Same literal placeholder contract as JS; apostrophes/SQL remain unchanged.
        var matcher=java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(pattern);
        return matcher.replaceAll(m -> {
            int i=Integer.parseInt(m.group(1));
            return java.util.regex.Matcher.quoteReplacement(i<args.length?String.valueOf(args[i]):m.group());
        });
    }
    public static String literal(String source) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return text("ui."+java.util.HexFormat.of().formatHex(digest).substring(0,12),source);
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
