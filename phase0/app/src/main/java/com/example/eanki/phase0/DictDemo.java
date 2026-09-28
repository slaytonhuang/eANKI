package com.example.eanki.phase0;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo 3：載入 assets 裡的 ECDICT SQLite，查單字並印出釋義。
 *
 * <p>SPEC 第 8 節說這個 demo 最關鍵 —— 它要回答的不只是「查不查得到」，
 * 而是<b>字典檔多大、首次載入多久、單筆與批次查詢多快</b>，
 * 這三個數字會決定字典要內建還是首次啟動下載。所以每一步都有計時。
 *
 * <p>SQLite 沒辦法直接開 assets 裡的檔案（它在 APK 內是壓縮的，不是 fd），
 * 必須先整包複製到 app 私有目錄一次。這就是「首次啟動成本」的來源。
 */
final class DictDemo {

    interface Callback {
        void onLog(String line);

        void onError(String message);
    }

    private static final String ASSET_NAME = "ecdict_mini.db";
    private static final String TABLE = "stardict";

    /** 模擬 Phase 3 掃一張照片框出一整排字，一次批次查詢。 */
    private static final String[] BATCH = {
            "apple", "banana", "orange", "dictionary", "scanner",
            "vocabulary", "quick", "brown", "fox", "jump",
            "lazy", "dog", "review", "memory", "phrase",
            "sentence", "translate", "camera", "image", "offline",
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void run(Context context, String wordToLookUp, Callback callback) {
        io.execute(() -> {
            SQLiteDatabase db = null;
            try {
                File dbFile = ensureCopied(context, log(callback));
                if (dbFile == null) {
                    return;
                }

                long t0 = System.currentTimeMillis();
                db = SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                post(callback, String.format(Locale.US,
                        "開啟資料庫耗時 %d ms，共 %d 筆詞條",
                        System.currentTimeMillis() - t0, countRows(db)));

                lookUp(db, wordToLookUp, callback);
                batch(db, callback);

            } catch (Exception e) {
                main.post(() -> callback.onError("字典 demo 失敗：" + e));
            } finally {
                if (db != null) {
                    db.close();
                }
            }
        });
    }

    /** 首次啟動把 assets 的 db 複製到私有目錄。已存在且大小相同就跳過。 */
    private File ensureCopied(Context context, LineSink sink) {
        File dbFile = new File(context.getFilesDir(), ASSET_NAME);

        long assetSize;
        try (InputStream probe = context.getAssets().open(ASSET_NAME)) {
            assetSize = probe.available();
        } catch (Exception e) {
            sink.accept("讀不到 assets/" + ASSET_NAME + "：" + e);
            return null;
        }

        sink.accept(String.format(Locale.US,
                "assets/%s 解壓後 %.2f MB", ASSET_NAME, assetSize / 1048576.0));

        if (dbFile.exists() && dbFile.length() == assetSize) {
            sink.accept("已複製過，跳過（這是第二次之後啟動的情況）");
            return dbFile;
        }

        long t0 = System.currentTimeMillis();
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             OutputStream out = new FileOutputStream(dbFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            sink.accept("複製失敗：" + e);
            return null;
        }

        sink.accept(String.format(Locale.US,
                "首次複製完成，耗時 %d ms（這是使用者第一次開 App 要等的時間）",
                System.currentTimeMillis() - t0));
        return dbFile;
    }

    private int countRows(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    /** 單筆查詢 —— 印出完整釋義。 */
    private void lookUp(SQLiteDatabase db, String word, Callback callback) {
        long t0 = System.nanoTime();
        try (Cursor c = db.rawQuery(
                "SELECT word, phonetic, pos, translation, definition, tag, frq"
                        + " FROM " + TABLE + " WHERE word = ? COLLATE NOCASE LIMIT 1",
                new String[]{word})) {

            long elapsedUs = (System.nanoTime() - t0) / 1000;

            if (!c.moveToFirst()) {
                post(callback, "查無此字：" + word + "（" + elapsedUs + " us）");
                return;
            }

            post(callback, String.format(Locale.US, "查詢 \"%s\" 耗時 %d us", word, elapsedUs));
            post(callback, "  word        = " + c.getString(0));
            post(callback, "  phonetic    = /" + c.getString(1) + "/");
            post(callback, "  pos         = " + c.getString(2));
            post(callback, "  translation = " + oneLine(c.getString(3)));
            post(callback, "  definition  = " + oneLine(c.getString(4)));
            post(callback, "  tag         = " + c.getString(5));
            post(callback, "  frq         = " + c.getInt(6));
        }
    }

    /** 批次查詢 —— 這是 Phase 3 掃一張照片後真正會發生的事。 */
    private void batch(SQLiteDatabase db, Callback callback) {
        long t0 = System.currentTimeMillis();
        int hit = 0;

        for (String word : BATCH) {
            try (Cursor c = db.rawQuery(
                    "SELECT translation FROM " + TABLE + " WHERE word = ? COLLATE NOCASE LIMIT 1",
                    new String[]{word})) {
                if (c.moveToFirst()) {
                    hit++;
                }
            }
        }

        post(callback, String.format(Locale.US,
                "批次查 %d 個字：命中 %d，總耗時 %d ms（Phase 3 掃一張照片的成本）",
                BATCH.length, hit, System.currentTimeMillis() - t0));
    }

    private static String oneLine(String s) {
        return s == null ? "null" : s.replace('\n', '|');
    }

    private void post(Callback callback, String line) {
        main.post(() -> callback.onLog(line));
    }

    private LineSink log(Callback callback) {
        return line -> post(callback, line);
    }

    private interface LineSink {
        void accept(String line);
    }

    void shutdown() {
        io.shutdown();
    }
}
