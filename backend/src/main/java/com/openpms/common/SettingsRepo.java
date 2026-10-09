package com.openpms.common;

import static com.openpms.db.Tables.SETTINGS;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/** Instance settings (settings table, architecture.md §2 전역 설정 키) with code defaults. */
@Component
public class SettingsRepo {
    private final DSLContext db;

    public SettingsRepo(DSLContext db) {
        this.db = db;
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
