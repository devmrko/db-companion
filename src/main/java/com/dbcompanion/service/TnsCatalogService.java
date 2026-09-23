package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.AppException;
import static com.dbcompanion.common.exception.AppException.Code.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Reads the standard alias entries in an OCI-downloaded wallet; never returns descriptors. */
@Service
public class TnsCatalogService {
    private static final Pattern ALIAS = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\\(");
    private final WalletCatalogService wallets;

    public TnsCatalogService(WalletCatalogService wallets) { this.wallets = wallets; }

    public WalletCatalogService.Wallet wallet(String walletId) { return wallets.resolve(walletId); }

    public List<TnsOption> options(String walletId) {
        var wallet = wallet(walletId);
        try {
            var path = wallet.path().resolve("tnsnames.ora");
            if (Files.size(path) > 1_048_576) throw new AppException(TNS_FILE_TOO_LARGE);
            var text = Files.readString(path).replaceAll("(?m)#.*$", "");
            var options = ALIAS.matcher(text).results().map(match -> match.group(1)).distinct()
                    .map(alias -> new TnsOption(alias, description(alias))).toList();
            if (options.isEmpty()) throw new AppException(TNS_ALIASES_MISSING);
            return options;
        } catch (IOException ex) {
            throw new AppException(TNS_FILE_UNREADABLE);
        }
    }

    public void validate(String walletId, String alias) {
        if (options(walletId).stream().noneMatch(option -> option.alias().equals(alias))) {
            throw new AppException(TNS_ALIAS_INVALID);
        }
    }

    private String description(String alias) {
        var type = alias.substring(alias.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "low" -> UiMessages.text("ui.5c526bdc622f", "가벼운 조회 · 병렬 실행 없음 · 낮은 우선순위");
            case "medium" -> UiMessages.text("ui.5a455ee4438e", "분석·배치 · 병렬 실행 · 동시 실행 제한에 따라 대기");
            case "high" -> UiMessages.text("ui.98fc731e2380", "소수의 무거운 분석 · 높은 우선순위 · 병렬 실행·대기열");
            case "tp" -> UiMessages.text("ui.49c114f03753", "일반 온라인 트랜잭션 · 병렬 실행 없음");
            case "tpurgent" -> UiMessages.text("ui.d0441130f01b", "시간에 민감한 트랜잭션 · 최상위 우선순위 · 수동 병렬 지원");
            default -> UiMessages.text("ui.dd2af9926e66", "Wallet에 등록된 서비스 · DB 설정에서 실행 특성을 확인하세요");
        };
    }

    public record TnsOption(String alias, String description) {}
}
