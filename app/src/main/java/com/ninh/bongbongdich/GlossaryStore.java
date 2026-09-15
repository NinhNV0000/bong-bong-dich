package com.ninh.bongbongdich;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlossaryStore {

    private static final String PREFS_NAME = "custom_translation_glossary";
    private static final String KEY_ENTRIES = "entries";

    private static final String EXTERNAL_PREFS_NAME =
            "custom_translation_glossary_external";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LAST_SYNC_OK = "last_sync_ok";

    private static final String STORAGE_FOLDER_NAME = "BongBongDich";
    private static final String DICTIONARY_FILE_NAME = "tu-dien.json";
    private static final String BACKUP_FILE_NAME = "tu-dien-backup.json";
    private static final String JSON_MIME_TYPE = "application/json";
    private static final int MAX_DICTIONARY_BYTES = 2 * 1024 * 1024;

    private GlossaryStore() {
    }

    public static final class ConnectionResult {
        private final int importedCount;
        private final int totalCount;

        private ConnectionResult(int importedCount, int totalCount) {
            this.importedCount = importedCount;
            this.totalCount = totalCount;
        }

        public int getImportedCount() {
            return importedCount;
        }

        public int getTotalCount() {
            return totalCount;
        }
    }

    public static synchronized LinkedHashMap<String, String> load(
            Context context
    ) {
        return loadInternal(context);
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

        LinkedHashMap<String, String> entries = loadInternal(context);
        entries.put(cleanChinese, cleanVietnamese);
        save(context, entries);
    }

    public static synchronized void remove(Context context, String chinese) {
        LinkedHashMap<String, String> entries = loadInternal(context);
        entries.remove(chinese);
        save(context, entries);
    }

    public static synchronized void clear(Context context) {
        save(context, new LinkedHashMap<>());
    }

    public static synchronized boolean isExternalConnected(Context context) {
        return !getExternalPreferences(context)
                .getString(KEY_TREE_URI, "")
                .trim()
                .isEmpty();
    }

    public static synchronized boolean wasLastExternalSyncSuccessful(
            Context context
    ) {
        return getExternalPreferences(context)
                .getBoolean(KEY_LAST_SYNC_OK, false);
    }

    public static String getExternalLocationLabel() {
        return "Tài liệu/BongBongDich/" + DICTIONARY_FILE_NAME;
    }

    public static synchronized ConnectionResult connectExternalDirectory(
            Context context,
            Uri selectedTreeUri
    ) throws Exception {
        if (selectedTreeUri == null) {
            throw new IOException("Không nhận được thư mục đã chọn");
        }

        Uri storageDirectory = resolveStorageDirectory(
                context,
                selectedTreeUri,
                true
        );
        if (storageDirectory == null) {
            throw new IOException("Không tạo được thư mục BongBongDich");
        }

        LinkedHashMap<String, String> currentEntries = loadInternal(context);
        LinkedHashMap<String, String> mergedEntries =
                new LinkedHashMap<>(currentEntries);
        int importedCount = 0;

        Uri dictionaryFile = findChild(
                context,
                selectedTreeUri,
                storageDirectory,
                DICTIONARY_FILE_NAME
        );
        if (dictionaryFile != null) {
            String raw = readText(context, dictionaryFile);
            if (!raw.trim().isEmpty()) {
                LinkedHashMap<String, String> externalEntries =
                        parseExternalSnapshot(raw);
                importedCount = externalEntries.size();

                // Từ vừa nhập trong app được ưu tiên nếu trùng với bản cũ.
                externalEntries.putAll(currentEntries);
                mergedEntries = normalizeAndSort(externalEntries);
            }
        }

        writeExternalSnapshot(
                context,
                selectedTreeUri,
                storageDirectory,
                mergedEntries
        );
        saveInternal(context, mergedEntries);

        getExternalPreferences(context)
                .edit()
                .putString(KEY_TREE_URI, selectedTreeUri.toString())
                .putBoolean(KEY_LAST_SYNC_OK, true)
                .apply();

        return new ConnectionResult(importedCount, mergedEntries.size());
    }

    private static LinkedHashMap<String, String> loadInternal(
            Context context
    ) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
        String raw = preferences.getString(KEY_ENTRIES, "{}");

        try {
            return parseEntriesObject(new JSONObject(raw == null ? "{}" : raw));
        } catch (Exception ignored) {
            // Không để một tệp hỏng làm ứng dụng bị dừng.
            return new LinkedHashMap<>();
        }
    }

    private static void save(
            Context context,
            Map<String, String> entries
    ) {
        LinkedHashMap<String, String> normalized = normalizeAndSort(entries);
        saveInternal(context, normalized);

        if (!isExternalConnected(context)) {
            return;
        }

        boolean syncOk = false;
        try {
            Uri treeUri = Uri.parse(
                    getExternalPreferences(context)
                            .getString(KEY_TREE_URI, "")
            );
            Uri storageDirectory = resolveStorageDirectory(
                    context,
                    treeUri,
                    true
            );
            if (storageDirectory != null) {
                writeExternalSnapshot(
                        context,
                        treeUri,
                        storageDirectory,
                        normalized
                );
                syncOk = true;
            }
        } catch (Exception ignored) {
            syncOk = false;
        }

        getExternalPreferences(context)
                .edit()
                .putBoolean(KEY_LAST_SYNC_OK, syncOk)
                .apply();
    }

    private static void saveInternal(
            Context context,
            Map<String, String> entries
    ) {
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

    private static SharedPreferences getExternalPreferences(Context context) {
        return context.getSharedPreferences(
                EXTERNAL_PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

    private static Uri resolveStorageDirectory(
            Context context,
            Uri treeUri,
            boolean createIfMissing
    ) throws Exception {
        String selectedId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri selectedDirectory = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                selectedId
        );

        String selectedName = queryDisplayName(context, selectedDirectory);
        if (STORAGE_FOLDER_NAME.equals(selectedName)) {
            return selectedDirectory;
        }

        Uri existing = findChild(
                context,
                treeUri,
                selectedDirectory,
                STORAGE_FOLDER_NAME
        );
        if (existing != null || !createIfMissing) {
            return existing;
        }

        return DocumentsContract.createDocument(
                context.getContentResolver(),
                selectedDirectory,
                DocumentsContract.Document.MIME_TYPE_DIR,
                STORAGE_FOLDER_NAME
        );
    }

    private static Uri findChild(
            Context context,
            Uri treeUri,
            Uri parentDirectory,
            String displayName
    ) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parentDirectory);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentId
        );

        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };

        try (Cursor cursor = context.getContentResolver().query(
                childrenUri,
                projection,
                null,
                null,
                null
        )) {
            if (cursor == null) {
                return null;
            }

            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (displayName.equals(name)) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(0)
                    );
                }
            }
        }
        return null;
    }

    private static String queryDisplayName(Context context, Uri documentUri) {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = context.getContentResolver().query(
                documentUri,
                projection,
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
            // Một số trình quản lý tệp không trả tên thư mục.
        }
        return "";
    }

    private static void writeExternalSnapshot(
            Context context,
            Uri treeUri,
            Uri storageDirectory,
            Map<String, String> entries
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri dictionaryFile = findChild(
                context,
                treeUri,
                storageDirectory,
                DICTIONARY_FILE_NAME
        );
        if (dictionaryFile == null) {
            dictionaryFile = DocumentsContract.createDocument(
                    resolver,
                    storageDirectory,
                    JSON_MIME_TYPE,
                    DICTIONARY_FILE_NAME
            );
        }
        if (dictionaryFile == null) {
            throw new IOException("Không tạo được tệp từ điển");
        }

        Uri backupFile = findChild(
                context,
                treeUri,
                storageDirectory,
                BACKUP_FILE_NAME
        );
        if (backupFile == null) {
            backupFile = DocumentsContract.createDocument(
                    resolver,
                    storageDirectory,
                    JSON_MIME_TYPE,
                    BACKUP_FILE_NAME
            );
        }

        String snapshot = serializeExternalSnapshot(entries);
        String previous = "";
        try {
            previous = readText(context, dictionaryFile);
        } catch (Exception ignored) {
            previous = "";
        }

        if (backupFile != null) {
            String backupContent = previous.trim().isEmpty()
                    ? snapshot
                    : previous;
            if (!snapshot.equals(previous) || previous.trim().isEmpty()) {
                writeText(context, backupFile, backupContent);
            }
        }
        writeText(context, dictionaryFile, snapshot);
    }

    private static String serializeExternalSnapshot(
            Map<String, String> entries
    ) throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }

        JSONObject root = new JSONObject();
        root.put("format", 1);
        root.put("entries", values);
        return root.toString(2);
    }

    private static LinkedHashMap<String, String> parseExternalSnapshot(
            String raw
    ) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONObject entries = root.optJSONObject("entries");
        if (entries == null) {
            // Hỗ trợ cả tệp JSON cũ chỉ chứa trực tiếp các cặp từ.
            entries = root;
        }
        return parseEntriesObject(entries);
    }

    private static LinkedHashMap<String, String> parseEntriesObject(
            JSONObject object
    ) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();

        while (keys.hasNext()) {
            String storedChinese = keys.next();
            String chinese = storedChinese.trim();
            String vietnamese = object.optString(storedChinese, "").trim();
            if (!chinese.isEmpty() && !vietnamese.isEmpty()) {
                result.put(chinese, vietnamese);
            }
        }
        return normalizeAndSort(result);
    }

    private static LinkedHashMap<String, String> normalizeAndSort(
            Map<String, String> source
    ) {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String chinese = entry.getKey() == null
                    ? ""
                    : entry.getKey().trim();
            String vietnamese = entry.getValue() == null
                    ? ""
                    : entry.getValue().trim();
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

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String readText(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Không mở được tệp từ điển");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DICTIONARY_BYTES) {
                    throw new IOException("Tệp từ điển quá lớn");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(
            Context context,
            Uri uri,
            String content
    ) throws IOException {
        OutputStream output = null;
        try {
            output = context.getContentResolver().openOutputStream(uri, "wt");
        } catch (Exception ignored) {
            // Một số trình quản lý tệp chỉ hỗ trợ chế độ ghi thông thường.
        }
        if (output == null) {
            output = context.getContentResolver().openOutputStream(uri, "w");
        }
        if (output == null) {
            throw new IOException("Không ghi được tệp từ điển");
        }

        try (OutputStream closeableOutput = output) {
            closeableOutput.write(content.getBytes(StandardCharsets.UTF_8));
            closeableOutput.flush();
        }
    }
}
