package com.openpms.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리 · 달력과 날짜 · 날짜 형식: how dates and times are written, and the first day of the week, for everyone.
 * "default" follows the language (Korean: 2026.10.21, 24-hour clock, weeks from Sunday). The server writes dates the
 * same way in journals, custom field values and mail ({@link Dates#format}).
 */
@Component
public class DisplayFormats {
    public record Settings(String dateFormat, String timeFormat, String weekStart) {}

    public static final List<String> DATE_FORMATS = List.of("default", "YYYY.MM.DD", "YYYY-MM-DD", "YYYY/MM/DD", "YYYY년 M월 D일", "DD.MM.YYYY", "MM/DD/YYYY");
    public static final List<String> TIME_FORMATS = List.of("default", "24h", "12h");
    /** ISO weekdays: Monday 1, Saturday 6, Sunday 7 */
    public static final List<String> WEEK_STARTS = List.of("default", "1", "6", "7");

    private static final Map<String, String> JAVA_PATTERNS = Map.of(
            "YYYY.MM.DD", "yyyy.MM.dd", "YYYY-MM-DD", "yyyy-MM-dd", "YYYY/MM/DD", "yyyy/MM/dd", "YYYY년 M월 D일", "yyyy년 M월 d일",
            "DD.MM.YYYY", "dd.MM.yyyy", "MM/DD/YYYY", "MM/dd/yyyy");

    private final SettingsRepo settings;
    private final JsonMapper json;

    public DisplayFormats(SettingsRepo settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    private String text(String key, List<String> allowed) {
        var v = settings.text(key, "default");
        return allowed.contains(v) ? v : "default";
    }

    public Settings current() {
        return new Settings(text("calendar.date_format", DATE_FORMATS), text("calendar.time_format", TIME_FORMATS), text("calendar.week_starts_on", WEEK_STARTS));
    }

    public Settings save(Settings in, UUID by) {
        if (in.dateFormat() == null || !DATE_FORMATS.contains(in.dateFormat())) throw ApiException.field("dateFormat", "날짜 형식을 고르세요.");
        if (in.timeFormat() == null || !TIME_FORMATS.contains(in.timeFormat())) throw ApiException.field("timeFormat", "시간 형식을 고르세요.");
        if (in.weekStart() == null || !WEEK_STARTS.contains(in.weekStart())) throw ApiException.field("weekStart", "주 시작 요일을 고르세요.");
        settings.put("calendar.date_format", json.writeValueAsString(in.dateFormat()), by);
        settings.put("calendar.time_format", json.writeValueAsString(in.timeFormat()), by);
        // the seed stores the weekday as a number
        settings.put("calendar.week_starts_on", "default".equals(in.weekStart()) ? "\"default\"" : in.weekStart(), by);
        apply(in);
        return current();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        apply(current());
    }

    private static void apply(Settings s) {
        Dates.usePattern(JAVA_PATTERNS.getOrDefault(s.dateFormat(), "yyyy.MM.dd"));
    }
}
