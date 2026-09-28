package com.example.eanki.data.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.DailyLimits;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 設定頁的每日上限<b>真的會決定佇列大小</b>。
 *
 * <p>這條測試存在的理由：上限原本是寫死的常數，接上設定之後，
 * 只要有人漏改一處呼叫（{@code DailyLimits.defaults()} 還留在某個 ViewModel 裡），
 * 畫面上滑桿會動、實際份量卻不變 —— 而那要好幾天後才會被發現。
 *
 * <p>設定寫在獨立的 SharedPreferences 檔，不碰正式設定。
 */
@RunWith(AndroidJUnit4.class)
public class SettingsDriveQueueTest {

    private static final String TEST_PREFS = "eanki_settings_test";

    private UserDatabase db;
    private WordDao wordDao;
    private AppSettings settings;
    private ReviewRepository reviews;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        UserDatabase.overrideForTest(db);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();

        settings = AppSettings.forTest(context, TEST_PREFS);
        settings.clearForTest();
        AppSettings.overrideForTest(settings);

        wordDao = db.wordDao();
        reviews = ReviewRepository.get(context);
    }

    @After
    public void tearDown() {
        settings.clearForTest();
        AppSettings.overrideForTest(null);
        UserDatabase.overrideForTest(null);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();
        db.close();
    }

    private void addWords(int count) {
        for (int i = 0; i < count; i++) {
            WordEntry w = new WordEntry();
            w.headword = "word" + i;
            w.source = Source.MANUAL;
            w.createdAt = System.currentTimeMillis() + i;
            wordDao.insert(w);
        }
    }

    private int preview() {
        return reviews.previewTodayCount(
                Direction.EN_TO_ZH, settings.limits(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------

    @Test
    public void 沒設定過時用SPEC的預設值() {
        DailyLimits d = settings.limits();
        assertEquals(10, d.newPerDay);
        assertEquals(60, d.reviewsPerDay);
    }

    @Test
    public void 調低新字上限_今天的佇列就變小() {
        addWords(30);
        assertEquals("預設 10", 10, preview());

        settings.setNewPerDay(3);
        assertEquals("改成 3 之後佇列就該是 3", 3, preview());
    }

    /** 新字上限 0 是合法的：先把手上的消化完。 */
    @Test
    public void 新字上限設成零_今天就不放新字() {
        addWords(30);
        settings.setNewPerDay(0);
        assertEquals(0, preview());
    }

    @Test
    public void 複習上限會蓋住總量() {
        addWords(30);
        settings.setNewPerDay(30);
        settings.setReviewsPerDay(5);
        assertEquals("總量天花板是 5", 5, preview());
    }

    /**
     * SharedPreferences 可能被備份還原或舊版本寫進亂數。
     * 讀出來沒夾限的話，每日份量就被那個數字接管了。
     */
    @Test
    public void 被寫壞的設定值會被夾回合法範圍() {
        settings.setNewPerDay(9999);
        settings.setReviewsPerDay(-1);
        DailyLimits d = settings.limits();
        assertEquals(DailyLimits.MAX_NEW_PER_DAY, d.newPerDay);
        assertEquals(DailyLimits.MIN_REVIEWS_PER_DAY, d.reviewsPerDay);
    }

    @Test
    public void 提醒設定可以存取而且有合理預設() {
        assertTrue("每日提醒預設開啟", settings.reminderEnabled());
        assertTrue("「沒有待複習就不提醒」預設開啟（SPEC 6.7）", settings.skipEmptyReminder());
        assertEquals(21, settings.reminderHour());
        assertEquals(0, settings.reminderMinute());

        settings.setReminderTime(7, 30);
        assertEquals(7, settings.reminderHour());
        assertEquals(30, settings.reminderMinute());

        // 超出範圍的時間要被夾掉，否則 Calendar 會算出奇怪的排程時間
        settings.setReminderTime(99, 99);
        assertEquals(23, settings.reminderHour());
        assertEquals(59, settings.reminderMinute());
    }

    @Test
    public void 發音設定有合理預設而且會夾限() {
        assertEquals(1.0f, settings.speechRate(), 0.001f);
        org.junit.Assert.assertFalse("翻卡自動發音預設關閉", settings.autoSpeakOnFlip());

        settings.setSpeechRate(9f);
        assertEquals(AppSettings.MAX_SPEECH_RATE, settings.speechRate(), 0.001f);
        settings.setSpeechRate(0f);
        assertEquals(AppSettings.MIN_SPEECH_RATE, settings.speechRate(), 0.001f);
    }
}
