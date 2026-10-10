package com.openpms.common;

import static com.openpms.db.Tables.SETTINGS;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/** Instance settings (settings table, architecture.md §2 전역 설정 키) with code defaults. */
@Component
public class SettingsRepo {
    private final DSLContext db;
    private final tools.jackson.databind.json.JsonMapper json;

    public SettingsRepo(DSLContext db, tools.jackson.databind.json.JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private String raw(String key) {
        var v = db.select(SETTINGS.VALUE).from(SETTINGS).where(SETTINGS.KEY.eq(key)).fetchOne(SETTINGS.VALUE);
        return v == null ? null : v.data().trim();
    }

    public boolean bool(String key, boolean fallback) {
        var v = raw(key);
        return v == null ? fallback : "true".equals(v) ? true : "false".equals(v) ? false : fallback;
    }

    /** A JSON array of strings; empty when missing or something else. */
    public java.util.List<String> strings(String key) {
        var v = raw(key);
        if (v == null) return java.util.List.of();
        try {
            var list = json.readValue(v, java.util.List.class);
            return ((java.util.List<?>) list).stream().map(String::valueOf).toList();
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** A JSON string value, or the bare value (a number stored without quotes); fallback when missing. */
    public String text(String key, String fallback) {
        var v = raw(key);
        if (v == null) return fallback;
        try {
            var parsed = json.readValue(v, Object.class);
            return parsed == null ? fallback : String.valueOf(parsed);
        } catch (RuntimeException e) {
            return v;
        }
    }

    public int integer(String key, int fallback) {
        var v = db.select(SETTINGS.VALUE).from(SETTINGS).where(SETTINGS.KEY.eq(key)).fetchOne(SETTINGS.VALUE);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.data().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void put(String key, String jsonValue, java.util.UUID by) {
        db.insertInto(SETTINGS).set(SETTINGS.KEY, key).set(SETTINGS.VALUE, JSONB.valueOf(jsonValue)).set(SETTINGS.UPDATED_BY, by)
                .onConflict(SETTINGS.KEY).doUpdate().set(SETTINGS.VALUE, JSONB.valueOf(jsonValue)).set(SETTINGS.UPDATED_BY, by)
                .execute();
    }
}
