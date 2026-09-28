package com.example.eanki.review;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * 「過去七天」的滾動視窗。SPEC 第 6.7 節：
 * <b>熱力圖用「過去七天」滾動而非日曆週</b>，週一打開才不會只看到一格。
 *
 * <p>純 Java，不依賴 Android 或 Room，可以單獨測試。
 * 日期格式必須跟 {@code ReviewLogDao} 裡 SQLite 的
 * {@code strftime('%Y-%m-%d', ..., 'localtime')} 一致，否則對不上。
 */
public final class SevenDayWindow {

    public static final int DAYS = 7;

    /** 索引 0 是最舊的一天，索引 6 是今天。 */
    public static String[] dayKeys(long now) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, -(DAYS - 1));

        String[] keys = new String[DAYS];
        for (int i = 0; i < DAYS; i++) {
            keys[i] = format.format(c.getTime());
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return keys;
    }

    /** 每一格對應星期幾，1=週日 … 7=週六（{@link Calendar#DAY_OF_WEEK}）。 */
    public static int[] dayOfWeek(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, -(DAYS - 1));

        int[] days = new int[DAYS];
        for (int i = 0; i < DAYS; i++) {
            days[i] = c.get(Calendar.DAY_OF_WEEK);
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return days;
    }

    /** 把「日期 → 張數」攤成七格，沒有紀錄的日子是 0。 */
    public static int[] toBuckets(Map<String, Integer> countsByDay, long now) {
        String[] keys = dayKeys(now);
        int[] buckets = new int[DAYS];
        for (int i = 0; i < DAYS; i++) {
            Integer value = countsByDay.get(keys[i]);
            buckets[i] = value == null ? 0 : value;
        }
        return buckets;
    }

    /**
     * 熱力圖的顏色強度，0–4 共五階（0 = 當天沒複習）。
     *
     * <p>用固定門檻而不是「相對於該週最大值」：相對刻度會讓
     * 「只複習 3 張的一週」看起來跟「複習 60 張的一週」一樣滿，
     * 使用者無法從顏色判斷自己那天到底做了多少。
     */
    public static int intensity(int count) {
        if (count <= 0) {
            return 0;
        }
        if (count < 5) {
            return 1;
        }
        if (count < 15) {
            return 2;
        }
        if (count < 30) {
            return 3;
        }
        return 4;
    }

    private SevenDayWindow() {
    }
}
