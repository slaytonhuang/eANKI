package com.example.eanki.data.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.eanki.review.DailyLimits;

/**
 * 使用者設定。SPEC 第 5 節設定頁。
 *
 * <p>用 SharedPreferences 而不是 Room：這些是單一使用者的少數幾個純量，
 * 沒有查詢、沒有關聯，開一張表只會多一次 migration 的機會。
 *
 * <p><b>讀出來的值一律經過夾限</b>（{@link DailyLimits#clamped}）。
 * SharedPreferences 可能被備份還原或舊版本寫進超出範圍的值，
 * 直接信任它等於讓每日份量被任意數字接管。
 */
public final class AppSettings {

    private static final String FILE = "eanki_settings";

    private static final String KEY_NEW_PER_DAY = "new_per_day";
    private static final String KEY_REVIEWS_PER_DAY = "reviews_per_day";
    private static final String KEY_REMINDER_ENABLED = "reminder_enabled";
    private static final String KEY_REMINDER_HOUR = "reminder_hour";
    private static final String KEY_REMINDER_MINUTE = "reminder_minute";
    private static final String KEY_SKIP_EMPTY_REMINDER = "skip_empty_reminder";
    private static final String KEY_SPEECH_RATE = "speech_rate";
    private static final String KEY_AUTO_SPEAK = "auto_speak_on_flip";
    private static final String KEY_LAST_BACKUP_AT = "last_backup_at";

    /** 預設晚上 9 點。 */
    public static final int DEFAULT_REMINDER_HOUR = 21;
    public static final int DEFAULT_REMINDER_MINUTE = 0;

    public static final float MIN_SPEECH_RATE = 0.5f;
    public static final float MAX_SPEECH_RATE = 1.5f;
    public static final float DEFAULT_SPEECH_RATE = 1.0f;

    private static volatile AppSettings instance;

    private final SharedPreferences prefs;

    private AppSettings(Context context, String fileName) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(fileName, Context.MODE_PRIVATE);
    }

    /**
     * <b>測試專用</b>：用另一個 SharedPreferences 檔名建一份。
     *
     * <p>測試絕對不可以寫到正式的設定檔。這個專案發生過一次
     * 「測試把使用者資料清光」，設定雖然比單字本便宜，同樣不該被測試改掉。
     */
    @VisibleForTesting
    public static AppSettings forTest(Context context, String fileName) {
        return new AppSettings(context, fileName);
    }

    /** <b>測試專用</b>：換掉單例，傳 null 還原。 */
    @VisibleForTesting
    public static void overrideForTest(@Nullable AppSettings replacement) {
        synchronized (AppSettings.class) {
            instance = replacement;
        }
    }

    /** <b>測試專用</b>：清空這一份設定。 */
    @VisibleForTesting
    public void clearForTest() {
        prefs.edit().clear().commit();
    }

    public static AppSettings get(Context context) {
        if (instance == null) {
            synchronized (AppSettings.class) {
                if (instance == null) {
                    instance = new AppSettings(context, FILE);
                }
            }
        }
        return instance;
    }

    // ---- 每日份量（SPEC 第 6.4 節）----

    @NonNull
    public DailyLimits limits() {
        return DailyLimits.clamped(
                prefs.getInt(KEY_NEW_PER_DAY, DailyLimits.DEFAULT_NEW_PER_DAY),
                prefs.getInt(KEY_REVIEWS_PER_DAY, DailyLimits.DEFAULT_REVIEWS_PER_DAY));
    }

    public void setNewPerDay(int value) {
        prefs.edit().putInt(KEY_NEW_PER_DAY, value).apply();
    }

    public void setReviewsPerDay(int value) {
        prefs.edit().putInt(KEY_REVIEWS_PER_DAY, value).apply();
    }

    // ---- 提醒（SPEC 第 6.7 節）----

    public boolean reminderEnabled() {
        return prefs.getBoolean(KEY_REMINDER_ENABLED, true);
    }

    public void setReminderEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply();
    }

    public int reminderHour() {
        return clamp(prefs.getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR), 0, 23);
    }

    public int reminderMinute() {
        return clamp(prefs.getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE), 0, 59);
    }

    public void setReminderTime(int hour, int minute) {
        prefs.edit()
                .putInt(KEY_REMINDER_HOUR, clamp(hour, 0, 23))
                .putInt(KEY_REMINDER_MINUTE, clamp(minute, 0, 59))
                .apply();
    }

    /**
     * 沒有待複習就不發通知。<b>預設開啟。</b>
     *
     * <p>SPEC 第 6.7 節直接點名理由：連續的空通知會讓使用者
     * 乾脆把通知權限關掉，之後真的有東西要複習時就再也提醒不到了。
     */
    public boolean skipEmptyReminder() {
        return prefs.getBoolean(KEY_SKIP_EMPTY_REMINDER, true);
    }

    public void setSkipEmptyReminder(boolean value) {
        prefs.edit().putBoolean(KEY_SKIP_EMPTY_REMINDER, value).apply();
    }

    // ---- 發音 ----

    public float speechRate() {
        float rate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE);
        return Math.max(MIN_SPEECH_RATE, Math.min(MAX_SPEECH_RATE, rate));
    }

    public void setSpeechRate(float value) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, value).apply();
    }

    /**
     * 翻到背面時自動唸單字。<b>預設關閉。</b>
     *
     * <p>複習常常在通勤或別人旁邊進行，突然出聲比沒出聲糟得多；
     * 想要的人自己打開一次就好。
     */
    public boolean autoSpeakOnFlip() {
        return prefs.getBoolean(KEY_AUTO_SPEAK, false);
    }

    public void setAutoSpeakOnFlip(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_SPEAK, value).apply();
    }

    // ---- 備份 ----

    /**
     * 上次成功匯出的時間，0 代表從來沒有備份過。
     *
     * <p>顯示出來是有用的：備份的價值取決於它有多新，
     * 而「我上次備份是什麼時候」是使用者自己記不住的事。
     */
    public long lastBackupAt() {
        return prefs.getLong(KEY_LAST_BACKUP_AT, 0L);
    }

    public void setLastBackupAt(long millis) {
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, millis).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
