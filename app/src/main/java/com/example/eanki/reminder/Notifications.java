package com.example.eanki.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import com.example.eanki.R;
import com.example.eanki.ui.review.ReviewActivity;

/** 每日提醒通知的組裝。 */
final class Notifications {

    private static final String CHANNEL_ID = "daily_review";
    private static final int NOTIFICATION_ID = 1001;

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notif_channel_desc));
        channel.setShowBadge(true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 發出提醒。點擊直接進複習畫面。
     *
     * @return 是否真的送出（沒有通知權限時會是 false）
     */
    static boolean showDueReminder(Context context, int dueCount) {
        ensureChannel(context);

        // 用 TaskStackBuilder，讓使用者從通知進複習畫面後按返回會回到單字本，
        // 而不是直接離開 App
        Intent intent = new Intent(context, ReviewActivity.class);
        TaskStackBuilder stack = TaskStackBuilder.create(context);
        stack.addNextIntentWithParentStack(intent);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = stack.getPendingIntent(0, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(context.getResources().getQuantityString(
                        R.plurals.notif_body, dueCount, dueCount))
                .setColor(context.getColor(R.color.mint))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();

        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return false;
        }
        try {
            manager.notify(NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException e) {
            // Android 13+ 沒有 POST_NOTIFICATIONS 權限
            return false;
        }
    }

    private Notifications() {
    }
}
