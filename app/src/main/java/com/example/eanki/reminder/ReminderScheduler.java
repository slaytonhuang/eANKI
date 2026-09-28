package com.example.eanki.reminder;

import android.content.Context;
import android.util.Log;

import com.example.eanki.data.settings.AppSettings;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 每日提醒的排程。SPEC 第 2 節指定 WorkManager。
 *
 * <p><b>為什麼用會自我重排的 OneTimeWorkRequest，而不是 PeriodicWorkRequest。</b>
 * PeriodicWorkRequest 的下一輪是從「這一輪實際執行的時間」起算，
 * 而 WorkManager 本來就允許延後執行（Doze、電池最佳化）。
 * 只要延後 20 分鐘，下一輪就變成 21:20，再下一輪 21:45……幾週後會漂到半夜。
 * 每次執行完重新算出「下一個 21:00」才不會累積漂移，
 * 也能自然處理時區變更與日光節約時間。
 *
 * <p>提醒時間與開關來自設定頁（{@link AppSettings}）。
 * 設定一改就要重新呼叫 {@link #schedule}，否則會等到下一次 Worker 跑完
 * 才套用 —— 那可能是明天。
 */
public final class ReminderScheduler {

    /** 唯一的工作名稱。用 REPLACE 確保不會排到兩份。 */
    static final String WORK_NAME = "daily-review-reminder";

    /**
     * 依目前設定排下一次提醒。重複呼叫是安全的。
     * 提醒關閉時改為取消既有排程。
     */
    public static void schedule(Context context) {
        AppSettings settings = AppSettings.get(context);
        if (!settings.reminderEnabled()) {
            cancel(context);
            Log.i("ReminderScheduler", "提醒已關閉，取消排程");
            return;
        }
        scheduleAt(context, settings.reminderHour(), settings.reminderMinute());
    }

    static void scheduleAt(Context context, int hour, int minute) {
        long delay = millisUntilNext(System.currentTimeMillis(), hour, minute);

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);

        Log.i("ReminderScheduler", String.format(java.util.Locale.US,
                "下一次提醒排在 %d:%02d，%.2f 小時後", hour, minute, delay / 3600000.0));
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(WORK_NAME);
    }

    /**
     * 距離下一個 {@code hour:minute} 還有幾毫秒。
     * 若今天的時間已經過了就排到明天。
     *
     * <p>純算術，沒有副作用，方便單元測試。
     */
    static long millisUntilNext(long now, int hour, int minute) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(now);
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        if (next.getTimeInMillis() <= now) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis() - now;
    }

    private ReminderScheduler() {
    }
}
