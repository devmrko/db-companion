package com.dbcompanion.common.i18n;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

@Configuration
public class I18nConfiguration {
    @Bean public MessageSource messageSource() { return UiMessages.source(); }
    @Bean public LocaleResolver localeResolver() {
        var resolver=new CookieLocaleResolver("DB_COMPANION_LANGUAGE") {
            @Override public org.springframework.context.i18n.LocaleContext resolveLocaleContext(jakarta.servlet.http.HttpServletRequest request) {
                return new org.springframework.context.i18n.SimpleLocaleContext(UiMessages.supported(super.resolveLocaleContext(request).getLocale()));
            }
        };
        resolver.setDefaultLocale(Locale.KOREAN);
        resolver.setCookieHttpOnly(true);
        resolver.setCookieSameSite("Strict");
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setRejectInvalidCookies(false);
        return resolver;
    }
}
