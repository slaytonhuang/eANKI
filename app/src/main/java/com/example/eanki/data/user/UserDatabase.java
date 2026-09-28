package com.example.eanki.data.user;

import android.content.Context;

import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.eanki.data.text.HanRadicalNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>使用者庫（讀寫）。SPEC 第 3 節 B。</b>
 *
 * <p>這裡<b>只有使用者自己的資料</b>：單字、排程、例句、標籤。
 * 很小，但必須能備份與匯出。
 *
 * <p><b>字典絕對不要放進來。</b>字典是唯讀的、很大、可整包拋棄式更新，
 * 走 {@link com.example.eanki.data.dict.DictionaryRepository}，
 * 那邊直接開 {@code SQLiteDatabase} 而不是 Room。
 * 兩者混在同一個 DB 會讓「換一版字典」變成「使用者資料要跟著遷移」。
 *
 * <p>檔名刻意取 {@code eanki_user.db}，跟字典的 {@code ecdict_mini.db} 一眼可分。
 */
@Database(
        entities = {
                WordEntry.class,
                ReviewState.class,
                ExampleSentence.class,
                Tag.class,
                WordTag.class,
                ReviewLog.class
        },
        version = 4,
        exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class UserDatabase extends RoomDatabase {

    private static final String NAME = "eanki_user.db";

    /**
     * v1 → v2：{@code review_state} 補上 {@code released_at}，
     * 用來實作 SPEC 第 6.4 節的新字每日上限。
     *
     * <p>既有的卡片一律填 0，代表「不是今天放行的」，
     * 因此不會佔用今天的新字額度。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE review_state "
                    + "ADD COLUMN released_at INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static volatile UserDatabase instance;

    public abstract WordDao wordDao();

    public abstract ReviewStateDao reviewStateDao();

    public abstract ExampleDao exampleDao();

    public abstract ReviewLogDao reviewLogDao();

    public abstract TagDao tagDao();

    /**
     * v2 → v3：新增 {@code review_log}。
     *
     * <p>為了畫 SPEC 第 5 節的「過去七天熱力圖」。{@code review_state.last_reviewed}
     * 只留最後一次，畫不出每天的量。舊資料沒有歷史可補，熱力圖會從升級這天開始累積。
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS review_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "word_id INTEGER NOT NULL,"
                    + "direction TEXT NOT NULL,"
                    + "reviewed_at INTEGER NOT NULL,"
                    + "grade INTEGER NOT NULL,"
                    + "FOREIGN KEY(word_id) REFERENCES word_entry(id)"
                    + " ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_review_log_word_id"
                    + " ON review_log (word_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_review_log_reviewed_at"
                    + " ON review_log (reviewed_at)");
        }
    };

    /**
     * v3 → v4：<b>一次性修資料</b>，把釋義與例句裡的部首碼位還原成一般漢字。
     *
     * <p>結構完全沒變，這是純資料修復。修的是從 PDF 抽取文字的字型編碼陷阱：
     * 匯入的 1,464 筆裡有 594 筆（41%）的釋義用的是康熙部首／CJK 部首補充
     * 的碼位而不是一般漢字，肉眼看不出來，但中文搜尋對那些字完全失效
     * （搜「用」U+7528 回傳 0 筆，實際有 36 筆存的是 U+2F64）。
     *
     * <p>做成 migration 而不是開 App 時跑一次：migration 在 transaction 裡、
     * 保證只跑一次、而且有 instrumented test 釘住。開機修法要自己管旗標，
     * 中途被砍掉就會留下改到一半的資料。
     *
     * <p>換算邏輯在純 Java 的 {@link HanRadicalNormalizer}，
     * 與匯入流程共用同一份 —— 兩邊各寫一份遲早會不一致。
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            repairColumn(db, "word_entry", "translation");
            repairColumn(db, "example", "text_zh");
        }

        /** 逐列讀出、還原、只在真的變了的時候寫回。 */
        private void repairColumn(SupportSQLiteDatabase db, String table, String column) {
            List<long[]> ids = new ArrayList<>();
            List<String> fixed = new ArrayList<>();

            try (Cursor c = db.query("SELECT id, " + column + " FROM " + table
                    + " WHERE " + column + " IS NOT NULL")) {
                while (c.moveToNext()) {
                    String value = c.getString(1);
                    String repaired = HanRadicalNormalizer.normalize(value);
                    if (repaired != null && !repaired.equals(value)) {
                        ids.add(new long[]{c.getLong(0)});
                        fixed.add(repaired);
                    }
                }
            }

            for (int i = 0; i < ids.size(); i++) {
                db.execSQL("UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                        new Object[]{fixed.get(i), ids.get(i)[0]});
            }
            Log.i("UserDatabase", "v3→v4 修復 " + table + "." + column
                    + "：" + ids.size() + " 列");
        }
    };

    /**
     * <b>測試專用</b>：把單例換成 in-memory 的資料庫。
     *
     * <p>存在的理由是一次真實的資料損失：instrumented test 原本直接對正式資料庫
     * 呼叫 {@code clearAllTables()}，每跑一次測試就把使用者匯入的單字清光。
     * 測試不可以碰正式資料 —— 要換就從這裡換，測完傳 null 還原。
     */
    @VisibleForTesting
    public static void overrideForTest(@Nullable UserDatabase database) {
        synchronized (UserDatabase.class) {
            instance = database;
        }
    }

    public static UserDatabase get(Context context) {
        if (instance == null) {
            synchronized (UserDatabase.class) {
                if (instance == null) {
                    // 外鍵約束與 CASCADE 由 Room 自動開啟，不需額外設定。
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), UserDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return instance;
    }
}
