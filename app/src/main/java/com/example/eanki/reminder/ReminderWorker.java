package com.example.eanki.reminder;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.settings.AppSettings;

/**
 * 每日提醒。SPEC 第 6.7 節。
 *
 * <p><b>今天沒有待複習的字就不發通知。</b>SPEC 直接點名理由：
 * 連續的空通知會讓使用者乾脆把通知權限關掉，
 * 之後真的有東西要複習時就再也提醒不到了。
 * 設定頁可以關掉這個行為，但預設是開著的。
 *
 * <p>這個 Worker <b>不會寫任何資料</b>：它走
 * {@link ReviewRepository#previewTodayCount}，不是 startSession。
 * 否則使用者還沒打開 App，今天的新字額度就已經被背景任務消耗掉了。
 */
public class ReminderWorker extends Worker {

    private static final String TAG = "ReminderWorker";

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        AppSettings settings = AppSettings.get(context);

        // 提醒關掉了就什麼都不做，也不再排下一次
        if (!settings.reminderEnabled()) {
            Log.i(TAG, "提醒已關閉，不發通知也不重排");
            return Result.success();
        }

        int dueCount = ReviewRepository.get(context).previewTodayCount(
                Direction.EN_TO_ZH, settings.limits(), System.currentTimeMillis());

        if (dueCount <= 0 && settings.skipEmptyReminder()) {
            Log.i(TAG, "今天沒有待複習的字，不發通知（SPEC 6.7）");
        } else {
            boolean sent = Notifications.showDueReminder(context, dueCount);
            Log.i(TAG, "待複習 " + dueCount + " 個字，通知" + (sent ? "已送出" : "被系統擋下（無權限）"));
        }

        // 不論有沒有發通知，都要排下一次，否則提醒只會響一天
        ReminderScheduler.schedule(context);
        return Result.success();
    }
}
