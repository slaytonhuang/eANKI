package com.example.eanki.data.user;

import static org.junit.Assert.assertEquals;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * v3 → v4 的一次性資料修復。
 *
 * <p>修的是從 PDF 抽取文字的字型編碼陷阱：釋義裡的字用的是部首碼位而不是
 * 一般漢字，肉眼看不出來，但中文搜尋對那些字完全失效。
 *
 * <p><b>這條測試同時釘住兩件事</b>：字元真的被換掉了，
 * 以及<b>其他字元一個都沒被動到</b>。後者才是容易出事的地方 ——
 * 對整串做 NFKC 也能修好部首，但會順手把全形括號換成半形，
 * 使用者的釋義就悄悄變了樣。
 */
@RunWith(AndroidJUnit4.class)
public class RadicalRepairMigrationTest {

    private static final String TEST_DB = "radical-repair-test.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            UserDatabase.class,
            new java.util.ArrayList<>(),
            new FrameworkSQLiteOpenHelperFactory());

    private static void insertWord(SupportSQLiteDatabase db, long id,
                                   String headword, String translation) {
        db.execSQL("INSERT INTO word_entry (id, headword, translation, source, created_at)"
                + " VALUES (?, ?, ?, 'IMPORT', 0)", new Object[]{id, headword, translation});
    }

    private static String translationOf(SupportSQLiteDatabase db, long id) {
        try (Cursor c = db.query("SELECT translation FROM word_entry WHERE id = " + id)) {
            c.moveToFirst();
            return c.getString(0);
        }
    }

    @Test
    public void 部首碼位被還原成一般漢字_其餘字元原封不動() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);

        // 全部取自實際中招的資料
        insertWord(db, 1, "abuse", "v.濫⽤；辱罵；虐待");              // ⽤ 康熙部首
        insertWord(db, 2, "accelerate", "v.增加，增⻓；使加速；促進");  // ⻓ CJK 部首補充（簡體）
        insertWord(db, 3, "wind tunnel", "（檢查⻛壓的）⻛洞");    // ⻛ ×2，含全形括號
        insertWord(db, 4, "conference", "n.會議，討論會，協商會");          // 本來就乾淨
        db.execSQL("INSERT INTO example (id, word_id, text_en, text_zh, is_custom)"
                + " VALUES (1, 1, 'He abused it.', '他濫⽤了它。', 1)");
        db.close();

        db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true, UserDatabase.MIGRATION_3_4);

        assertEquals("v.濫用；辱罵；虐待", translationOf(db, 1));
        assertEquals("v.增加，增長；使加速；促進", translationOf(db, 2));
        // 全形括號 U+FF08/U+FF09 必須留著，不可以被 NFKC 換成半形
        assertEquals("（檢查風壓的）風洞", translationOf(db, 3));
        assertEquals("n.會議，討論會，協商會", translationOf(db, 4));

        try (Cursor c = db.query("SELECT text_zh FROM example WHERE id = 1")) {
            c.moveToFirst();
            assertEquals("他濫用了它。", c.getString(0));
        }
    }

    /**
     * 修完之後，用一般輸入法打得出來的字要搜得到。
     *
     * <p>修之前搜「用」(U+7528) 是 0 筆，而使用者打不出 U+2F64。
     */
    @Test
    public void 修完之後用一般漢字搜得到() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);
        insertWord(db, 1, "abuse", "v.濫⽤");
        insertWord(db, 2, "accessible", "a.可進⼊的");   // ⼊
        db.close();

        db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true, UserDatabase.MIGRATION_3_4);

        assertEquals(1, countLike(db, "%用%"));
        assertEquals(1, countLike(db, "%入%"));
        assertEquals("還原後不該再有部首碼位殘留", 0, countLike(db, "%⽤%"));
    }

    private static int countLike(SupportSQLiteDatabase db, String pattern) {
        try (Cursor c = db.query(
                "SELECT COUNT(*) FROM word_entry WHERE translation LIKE ?",
                new Object[]{pattern})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    /** 沒有部首碼位的資料庫跑過 migration 之後應該一個字都沒變。 */
    @Test
    public void 乾淨的資料跑過修復不會被改動() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);
        String clean = "n.勞動力，受僱用的人（注意等）";
        insertWord(db, 1, "workforce", clean);
        db.close();

        db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true, UserDatabase.MIGRATION_3_4);

        assertEquals(clean, translationOf(db, 1));
    }
}
