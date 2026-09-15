package com.ninh.bongbongdich;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlossaryStore {

    private static final String PREFS_NAME = "custom_translation_glossary";
    private static final String KEY_ENTRIES = "entries";

    private GlossaryStore() {
    }

    public static synchronized LinkedHashMap<String, String> load(Context context) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
        String raw = preferences.getString(KEY_ENTRIES, "{}");

        try {
            JSONObject object = new JSONObject(raw == null ? "{}" : raw);
            List<Map.Entry<String, String>> entries = new ArrayList<>();
            Iterator<String> keys = object.keys();

            while (keys.hasNext()) {
                String storedChinese = keys.next();
                String chinese = storedChinese.trim();
                String vietnamese = object.optString(storedChinese, "").trim();
                if (!chinese.isEmpty() && !vietnamese.isEmpty()) {
                    entries.add(new java.util.AbstractMap.SimpleEntry<>(
                            chinese,
                            vietnamese
                    ));
                }
            }

            entries.sort(
                    Comparator
                            .<Map.Entry<String, String>>comparingInt(
                                    entry -> entry.getKey().length()
                            )
                            .reversed()
                            .thenComparing(Map.Entry::getKey)
            );
            for (Map.Entry<String, String> entry : entries) {
                result.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
            // Nếu dữ liệu cũ bị lỗi, trả về từ điển trống để người dùng nhập lại.
        }
        return result;
    }

    public static synchronized void put(
            Context context,
            String chinese,
            String vietnamese
    ) {
        String cleanChinese = chinese == null ? "" : chinese.trim();
        String cleanVietnamese = vietnamese == null ? "" : vietnamese.trim();
        if (cleanChinese.isEmpty() || cleanVietnamese.isEmpty()) {
            return;
        }

        LinkedHashMap<String, String> entries = load(context);
        entries.put(cleanChinese, cleanVietnamese);
        save(context, entries);
    }

    public static synchronized void remove(Context context, String chinese) {
        LinkedHashMap<String, String> entries = load(context);
        entries.remove(chinese);
        save(context, entries);
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENTRIES)
                .apply();
    }

    private static void save(Context context, Map<String, String> entries) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
            return;
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, object.toString())
                .apply();
    }
}
