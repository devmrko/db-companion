package com.dbcompanion.service;

import com.dbcompanion.common.config.OracleSettings;
import com.dbcompanion.common.exception.AppException;
import static com.dbcompanion.common.exception.AppException.Code.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable server-side registry. Client IDs are never interpreted as filesystem paths. */
@Service
public class WalletCatalogService {
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final List<Wallet> wallets;
    private final AppException.Code error;

    public WalletCatalogService(OracleSettings settings) {
        List<Wallet> parsed = List.of();
        AppException.Code failure = null;
        try { parsed = parse(settings); }
        catch (RuntimeException ex) { failure = WALLET_LIST_INVALID; }
        wallets = parsed;
        error = failure;
    }

    public List<Wallet> wallets() {
        if (error != null) throw new AppException(error);
        if (wallets.isEmpty()) throw new AppException(WALLET_NOT_CONFIGURED);
        return wallets;
    }

    public Wallet resolve(String id) {
        var registered = wallets();
        if ((id == null || id.isBlank()) && registered.size() == 1) return registered.getFirst();
        return registered.stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> new AppException(WALLET_SELECTION_INVALID));
    }

    private static List<Wallet> parse(OracleSettings settings) {
        var paths = new LinkedHashSet<Path>();
        String raw = settings.walletPaths();
        // Accept the original requested ORACLE_WALLET_PATH=[...] spelling as well.
        if ((raw == null || raw.isBlank()) && settings.walletPath() != null && settings.walletPath().stripLeading().startsWith("["))
            raw = settings.walletPath();
        if (raw != null && !raw.isBlank()) {
            if (raw.length() > 131_072) throw new AppException(WALLET_LIST_INVALID);
            var root = JSON.readTree(raw);
            if (!root.isArray() || root.isEmpty() || root.size() > 32) throw new AppException(WALLET_LIST_INVALID);
            for (var item : root) {
                if (!item.isString()) throw new AppException(WALLET_LIST_INVALID);
                paths.add(path(item.stringValue(), true));
            }
        } else if (settings.walletPath() != null && !settings.walletPath().isBlank()) {
            paths.add(path(settings.walletPath(), false));
        }
        var result = new ArrayList<Wallet>();
        for (var p : paths) {
            String id = id(p), base = label(p);
            long duplicates = paths.stream().filter(x -> label(x).equals(base)).count();
            result.add(new Wallet(id, duplicates > 1 ? base + " · " + id.substring(0, 8) : base, p));
        }
        return List.copyOf(result);
    }

    private static Path path(String value, boolean absolute) {
        if (value.isBlank() || value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl))
            throw new AppException(WALLET_LIST_INVALID);
        Path path = Path.of(value);
        if (absolute && !path.isAbsolute()) throw new AppException(WALLET_LIST_INVALID);
        path = path.toAbsolutePath().normalize();
        if (path.getFileName() == null) throw new AppException(WALLET_LIST_INVALID);
        return path;
    }

    private static String id(Path path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String label(Path path) {
        String folder = path.getFileName().toString(), name = folder.replaceFirst("^Wallet_", "");
        return name.isBlank() ? folder : name;
    }

    /** Server-side only: never serialize this record to a client. */
    public record Wallet(String id, String name, Path path) {
        public Option option() { return new Option(id, name); }
    }
    public record Option(String id, String name) {}
}
