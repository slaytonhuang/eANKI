package com.example.eanki.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/** 過去七天滾動視窗。SPEC 第 6.7 節。 */
public class SevenDayWindowTest {

    private static long at(int year, int month, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day, hour, 30, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void 七格且最後一格是今天() {
        String[] keys = SevenDayWindow.dayKeys(at(2026, 9, 23, 10));

        assertEquals(7, keys.length);
        assertEquals("2026-09-17", keys[0]);
        assertEquals("2026-09-23", keys[6]);
    }

    /**
     * SPEC 第 6.7 節的重點：<b>滾動七天，不是日曆週。</b>
     * 週一打開時，日曆週只會有一格，滾動七天則仍是完整七格。
     */
    @Test
    public void 週一打開也是完整七格_不是只有一格() {
        // 2026-09-21 是星期一
        long monday = at(2026, 9, 21, 9);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(monday);
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK));

        String[] keys = SevenDayWindow.dayKeys(monday);
        assertEquals(7, keys.length);
        assertEquals("要往回看到上週二，而不是從本週一開始", "2026-09-15", keys[0]);
        assertEquals("2026-09-21", keys[6]);
    }

    @Test
    public void 跨月也要正確() {
        String[] keys = SevenDayWindow.dayKeys(at(2026, 10, 3, 12));
        assertEquals("2026-09-27", keys[0]);
        assertEquals("2026-10-03", keys[6]);
    }

    @Test
    public void 同一天不同時刻的分格相同() {
        String[] morning = SevenDayWindow.dayKeys(at(2026, 9, 23, 1));
        String[] night = SevenDayWindow.dayKeys(at(2026, 9, 23, 23));
        assertEquals(morning[6], night[6]);
        assertEquals(morning[0], night[0]);
    }

    @Test
    public void 隔天視窗會往前滾一格() {
        String[] today = SevenDayWindow.dayKeys(at(2026, 9, 23, 10));
        String[] tomorrow = SevenDayWindow.dayKeys(at(2026, 9, 24, 10));
        assertNotEquals(today[0], tomorrow[0]);
        assertEquals("今天的第 2 格會變成明天的第 1 格", today[1], tomorrow[0]);
    }

    @Test
    public void 星期標籤對得上() {
        // 2026-09-23 是星期三
        int[] days = SevenDayWindow.dayOfWeek(at(2026, 9, 23, 10));
        assertEquals(7, days.length);
        assertEquals(Calendar.WEDNESDAY, days[6]);
        assertEquals(Calendar.THURSDAY, days[0]);
    }

    @Test
    public void 攤成七格_沒紀錄的日子是零() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("2026-09-23", 12);
        counts.put("2026-09-20", 5);

        int[] buckets = SevenDayWindow.toBuckets(counts, at(2026, 9, 23, 10));

        assertEquals(7, buckets.length);
        assertEquals(12, buckets[6]);
        assertEquals(5, buckets[3]);
        assertEquals(0, buckets[0]);
        assertEquals(0, buckets[5]);
    }

    @Test
    public void 視窗外的紀錄不會被算進來() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("2026-09-10", 99);   // 兩週前

        int[] buckets = SevenDayWindow.toBuckets(counts, at(2026, 9, 23, 10));

        for (int b : buckets) {
            assertEquals(0, b);
        }
    }

    @Test
    public void 顏色強度用固定刻度() {
        assertEquals(0, SevenDayWindow.intensity(0));
        assertEquals(1, SevenDayWindow.intensity(1));
        assertEquals(1, SevenDayWindow.intensity(4));
        assertEquals(2, SevenDayWindow.intensity(5));
        assertEquals(2, SevenDayWindow.intensity(14));
        assertEquals(3, SevenDayWindow.intensity(15));
        assertEquals(4, SevenDayWindow.intensity(30));
        assertEquals(4, SevenDayWindow.intensity(200));
    }
}
