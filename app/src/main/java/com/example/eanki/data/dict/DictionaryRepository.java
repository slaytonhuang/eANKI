package com.example.eanki.data.dict;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * <b>字典庫（唯讀）。SPEC 第 3 節 A。</b>
 *
 * <p><b>刻意不用 Room。</b>字典是預先建好的 ECDICT SQLite，App 永不寫入，
 * 版本升級時整包替換。用 Room 會逼我們為一個唯讀檔維護 entity 與 migration，
 * 而換一版字典時那些 migration 全是多餘的。直接開
 * {@link SQLiteDatabase#OPEN_READONLY} 最貼近它真正的角色。
 *
 * <p>使用者庫在 {@link com.example.eanki.data.user.UserDatabase}，兩者不共用檔案。
 *
 * <p>Phase 0 實測（Galaxy Z Fold 5，五萬筆）：首次複製 60 ms、開啟 0 ms、
 * 單筆查詢 64 us、批次 20 字 1 ms。
 */
public final class DictionaryRepository {

    private static final String TAG = "DictRepo";
    private static final String ASSET_NAME = "ecdict_mini.db";
    private static final String TABLE = "stardict";
    /** 變化形 → 原形的索引，由 {@code tools/build_forms.py} 從 exchange 欄位反向展開。 */
    private static final String FORM_TABLE = "word_form";

    private static final String COLUMNS =
            "word, phonetic, translation, definition, pos, tag, frq";

    private static volatile DictionaryRepository instance;

    private final Context appContext;
    @Nullable
    private SQLiteDatabase db;

    private DictionaryRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static DictionaryRepository get(Context context) {
        if (instance == null) {
            synchronized (DictionaryRepository.class) {
                if (instance == null) {
                    instance = new DictionaryRepository(context);
                }
            }
        }
        return instance;
    }

    /**
     * 查一個字。找不到回傳 null。
     *
     * <p>輸入會先過 {@link WordNormalizer}，SQL 再用 {@code COLLATE NOCASE}
     * 擋掉剩下的大小寫差異。
     */
    @WorkerThread
    @Nullable
    public DictEntry lookUp(String raw) {
        String word = WordNormalizer.normalize(raw);
        if (word.isEmpty()) {
            return null;
        }

        SQLiteDatabase database = ensureOpen();
        if (database == null) {
            return null;
        }

        DictEntry direct = queryExact(database, word);
        if (direct != null) {
            return direct;
        }
        // 直接查不到才做詞形還原。順序不能反 ——
        // 有些字既是詞條又是別的字的變化形（saw 是鋸子，也是 see 的過去式），
        // 直接命中永遠優先。
        return queryByInflection(database, word);
    }

    @WorkerThread
    @Nullable
    private DictEntry queryExact(SQLiteDatabase database, String word) {
        try (Cursor c = database.rawQuery(
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE word = ? COLLATE NOCASE LIMIT 1",
                new String[]{word})) {
            return c.moveToFirst() ? read(c, null) : null;
        } catch (Exception e) {
            Log.e(TAG, "查詢失敗：" + word, e);
            return null;
        }
    }

    /**
     * 詞形還原：把 {@code jumps} 還原成 {@code jump} 再查釋義。
     *
     * <p>ECDICT 的 {@code exchange} 欄位存的是「原形 → 變化形」，
     * 而且變化形本身多半沒有獨立詞條（精簡版裡 jumps、walked、bigger、went 都查不到）。
     * 建置時已經把它反向展開成 {@code word_form} 表，這裡只要多查一次。
     *
     * <p>一個變化形可能對應多個原形（{@code analyses} → analyse / analysis），
     * 取<b>詞頻較高</b>的那個 —— 使用者掃到的多半是常用字。
     *
     * <p>回傳的 {@link DictEntry#word} 是原形，但 {@link DictEntry#matchedForm}
     * 記著使用者實際查的形式，畫面與存檔都用後者。
     *
     * <p><b>{@code is_headword = 0} 這個條件不能拿掉。</b>
     * {@code word_form} 裡也收了「本身就是詞條的變化形」（{@code abandoning}、
     * {@code running}），那是給例句備援用的。查釋義時把它們算進來，
     * 會讓有自己詞條的字被還原掉，顯示成原形的釋義。
     */
    @WorkerThread
    @Nullable
    private DictEntry queryByInflection(SQLiteDatabase database, String form) {
        try (Cursor c = database.rawQuery(
                "SELECT " + prefixed(COLUMNS) + " FROM " + FORM_TABLE + " f"
                        + " JOIN " + TABLE + " s ON s.word = f.lemma COLLATE NOCASE"
                        + " WHERE f.form = ? COLLATE NOCASE AND f.is_headword = 0"
                        + " ORDER BY CASE WHEN s.frq > 0 THEN s.frq ELSE 9999999 END ASC"
                        + " LIMIT 1",
                new String[]{form})) {
            return c.moveToFirst() ? read(c, form) : null;
        } catch (Exception e) {
            Log.e(TAG, "詞形還原查詢失敗：" + form, e);
            return null;
        }
    }

    private static String prefixed(String columns) {
        StringBuilder sb = new StringBuilder();
        for (String col : columns.split(", ")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("s.").append(col);
        }
        return sb.toString();
    }

    private static DictEntry read(Cursor c, @Nullable String matchedForm) {
        return new DictEntry(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getInt(6), matchedForm);
    }

    /**
     * 例句備援。SPEC 第 8 節 Phase 4：掃描到的字、字典查得到釋義但沒有例句時用。
     *
     * <p>查法跟 {@link #lookUp} 同一套：先直接查，查不到才做詞形還原。
     * 順序不能反 —— {@code saw} 是鋸子，也是 {@code see} 的過去式，
     * 直接命中永遠優先。
     *
     * <p><b>結果不寫進使用者的資料庫。</b>這是字典的附屬資料，
     * 字典整包更新就該跟著換；複製一份進 {@code example} 表只會讓備份變大、
     * 而且換字典之後留下對不上的舊句子。使用者自己的例句（{@code is_custom=1}）
     * 永遠優先顯示，這裡只在沒有的時候補位。
     */
    @WorkerThread
    @NonNull
    public java.util.List<DictExample> findExamples(@Nullable String raw, int limit) {
        java.util.List<DictExample> result = new java.util.ArrayList<>();
        if (raw == null || limit <= 0) {
            return result;
        }
        String word = WordNormalizer.normalize(raw);
        if (word.isEmpty()) {
            return result;
        }
        SQLiteDatabase database = ensureOpen();
        if (database == null) {
            return result;
        }
        queryExamples(database, word, limit, result);
        if (result.isEmpty()) {
            String lemma = lemmaOf(database, word);
            if (lemma != null) {
                queryExamples(database, lemma, limit, result);
            }
        }
        return result;
    }

    private static void queryExamples(SQLiteDatabase database, String word, int limit,
                                      java.util.List<DictExample> out) {
        try (Cursor c = database.rawQuery(
                "SELECT s.text_en, s.text_zh FROM tatoeba_index i"
                        + " JOIN tatoeba_sentence s ON s.id = i.sentence_id"
                        + " WHERE i.word = ? ORDER BY i.rank LIMIT ?",
                new String[]{word, String.valueOf(limit)})) {
            while (c.moveToNext()) {
                out.add(new DictExample(c.getString(0), c.getString(1)));
            }
        } catch (Exception e) {
            Log.w(TAG, "查例句失敗：" + word, e);
        }
    }

    /**
     * 例句專用的詞形還原。
     *
     * <p><b>不加 {@code is_headword = 0} 的條件</b>，跟查釋義那邊不同。
     * 那個條件存在是因為查釋義時直接命中必須優先 —— {@code saw} 是鋸子，
     * 不是 {@code see} 的過去式。
     *
     * <p>例句的需求相反：{@code abandoning} 沒有自己的例句時，
     * 拿 {@code abandon} 的完全合理，即使 {@code abandoning} 自己也是個詞條。
     */
    @Nullable
    private static String lemmaOf(SQLiteDatabase database, String form) {
        try (Cursor c = database.rawQuery(
                "SELECT lemma FROM word_form WHERE form = ? LIMIT 1", new String[]{form})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 字典是否已經可以用。設定頁的「離線字典狀態」會用到。 */
    @WorkerThread
    public boolean isReady() {
        return ensureOpen() != null;
    }

    /** 設定頁要顯示的字典狀態。 */
    public static final class Status {
        public final boolean ready;
        public final int entryCount;
        public final int formCount;
        public final int exampleCount;
        public final long fileSizeBytes;

        Status(boolean ready, int entryCount, int formCount, int exampleCount,
               long fileSizeBytes) {
            this.ready = ready;
            this.entryCount = entryCount;
            this.formCount = formCount;
            this.exampleCount = exampleCount;
            this.fileSizeBytes = fileSizeBytes;
        }
    }

    @WorkerThread
    @NonNull
    public Status status() {
        SQLiteDatabase database = ensureOpen();
        if (database == null) {
            return new Status(false, 0, 0, 0, 0L);
        }
        return new Status(true, count(database, "stardict"), count(database, "word_form"),
                count(database, "tatoeba_sentence"),
                new File(appContext.getFilesDir(), ASSET_NAME).length());
    }

    private static int count(SQLiteDatabase database, String table) {
        try (Cursor c = database.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            Log.w(TAG, "數不到 " + table, e);
            return 0;
        }
    }

    /**
     * 從 App 內建的檔案重新複製一份字典。
     *
     * <p>SPEC 第 3 節定案「內建精簡版，不做下載」，所以設定頁沒有「下載更新」——
     * 字典跟著 App 版本走。這個動作是給另一種情況用的：本機那份被截斷或損壞
     * （複製到一半被砍、儲存空間滿了）。平常不需要按。
     */
    @WorkerThread
    public boolean rebuild() {
        synchronized (this) {
            if (db != null) {
                db.close();
                db = null;
            }
        }
        File target = new File(appContext.getFilesDir(), ASSET_NAME);
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "刪不掉舊字典檔");
            return false;
        }
        return ensureOpen() != null;
    }

    @WorkerThread
    @Nullable
    private synchronized SQLiteDatabase ensureOpen() {
        if (db != null && db.isOpen()) {
            return db;
        }
        File file = ensureCopied();
        if (file == null) {
            return null;
        }
        try {
            db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            return db;
        } catch (Exception e) {
            Log.e(TAG, "開啟字典失敗", e);
            return null;
        }
    }

    /**
     * SQLite 開不了 assets 裡的檔案（在 APK 內是壓縮的，拿不到 fd），
     * 所以首次啟動要整包複製到私有目錄。比對檔案大小決定是否跳過。
     */
    @WorkerThread
    @Nullable
    private File ensureCopied() {
        File target = new File(appContext.getFilesDir(), ASSET_NAME);

        long assetSize;
        try (InputStream probe = appContext.getAssets().open(ASSET_NAME)) {
            assetSize = probe.available();
        } catch (Exception e) {
            Log.e(TAG, "讀不到 assets/" + ASSET_NAME, e);
            return null;
        }

        if (target.exists() && target.length() == assetSize) {
            return target;
        }

        long startedAt = System.currentTimeMillis();
        try (InputStream in = appContext.getAssets().open(ASSET_NAME);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.e(TAG, "複製字典失敗", e);
            // 複製到一半失敗會留下壞檔，下次大小比對不過會重來，但先刪乾淨比較保險
            target.delete();
            return null;
        }

        Log.i(TAG, "字典首次複製完成，耗時 " + (System.currentTimeMillis() - startedAt) + " ms");
        return target;
    }
}
