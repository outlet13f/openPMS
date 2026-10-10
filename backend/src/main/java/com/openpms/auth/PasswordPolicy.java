package com.openpms.auth;

import com.openpms.common.ApiException;
import com.openpms.common.SettingsRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 관리 · 로그인과 비밀번호 (settings auth.*): the password rules every path that sets a password checks, the lockout after failed
 * sign-ins, whether "비밀번호를 잊으셨나요?" works, and how long mailed links last. frontend/src/api/password-policy.ts mirrors check().
 */
@Component
public class PasswordPolicy {
    public static final List<String> CLASSES = List.of("lower", "upper", "digit", "special");
    private static final Map<String, String> CLASS_NAMES = Map.of("lower", "영문 소문자", "upper", "영문 대문자", "digit", "숫자", "special", "특수 문자");

    /** What a signed-out page may know: enough to say what a new password needs. */
    public record Rules(int minLength, List<String> requiredClasses, int minClasses, boolean resetEnabled) {}

    public record Settings(int minLength, List<String> requiredClasses, int minClasses, boolean resetEnabled, int lockoutAttempts, int lockoutMinutes,
                           int invitationTtlHours, int resetTtlMinutes) {
        public Rules rules() {
            return new Rules(minLength, requiredClasses, minClasses, resetEnabled);
        }
    }

    private final SettingsRepo settings;

    public PasswordPolicy(SettingsRepo settings) {
        this.settings = settings;
    }

    public Settings current() {
        var required = settings.strings("auth.password_required_classes").stream().filter(CLASSES::contains).toList();
        return new Settings(settings.integer("auth.password_min_length", 10), required, settings.integer("auth.password_min_classes", 0),
                settings.bool("auth.password_reset_enabled", true), settings.integer("auth.lockout_attempts", 5), settings.integer("auth.lockout_minutes", 15),
                settings.integer("auth.invitation_ttl_hours", 168), settings.integer("auth.password_reset_ttl_minutes", 60));
    }

    static boolean has(String password, String kind) {
        return switch (kind) {
            case "lower" -> password.chars().anyMatch(c -> c >= 'a' && c <= 'z');
            case "upper" -> password.chars().anyMatch(c -> c >= 'A' && c <= 'Z');
            case "digit" -> password.chars().anyMatch(c -> c >= '0' && c <= '9');
            default -> password.chars().anyMatch(c -> !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && !Character.isWhitespace(c));
        };
    }

    /** A 422 on `field` with what the password still needs, or nothing. */
    public void check(String field, String password) {
        var r = current();
        var p = password == null ? "" : password;
        if (p.length() < r.minLength()) throw ApiException.field(field, "비밀번호는 " + r.minLength() + "자 이상이어야 합니다.");
        if (p.length() > 128) throw ApiException.field(field, "비밀번호는 128자 이하로 정하세요.");
        var missing = new ArrayList<String>();
        for (var k : r.requiredClasses()) if (!has(p, k)) missing.add(CLASS_NAMES.get(k));
        if (!missing.isEmpty()) throw ApiException.field(field, "비밀번호에 " + String.join(", ", missing) + "을(를) 넣으세요.");
        long kinds = CLASSES.stream().filter(k -> has(p, k)).count();
        if (kinds < r.minClasses())
            throw ApiException.field(field, "영문 소문자·대문자·숫자·특수 문자 중 " + r.minClasses() + "가지 이상을 섞으세요.");
    }
}
