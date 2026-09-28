package com.example.eanki.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 掃描效能統計。純 Java，跑在 JVM 上。 */
public class ScanStatsTest {

    private static final double EPS = 0.001;

    @Test
    public void 沒有資料時是零() {
        ScanStats stats = new ScanStats();
        assertEquals(0, stats.averageLatencyMs(), EPS);
        assertEquals(0, stats.maxLatencyMs(), EPS);
        assertEquals(0, stats.analysisFps(), EPS);
    }

    @Test
    public void 平均與最大值() {
        ScanStats stats = new ScanStats();
        stats.record(100, 1000);
        stats.record(200, 1050);
        stats.record(150, 1100);

        assertEquals(150, stats.averageLatencyMs(), EPS);
        assertEquals(200, stats.maxLatencyMs(), EPS);
    }

    /** 只看最近 WINDOW 幀，久遠的尖峰不該一直掛在「最久」上。 */
    @Test
    public void 移動視窗會把舊資料擠掉() {
        ScanStats stats = new ScanStats();
        stats.record(5000, 1000);
        for (int i = 0; i < ScanStats.WINDOW; i++) {
            stats.record(100, 1000 + i);
        }
        assertEquals("尖峰已經被擠出視窗", 100, stats.maxLatencyMs(), EPS);
        assertEquals(100, stats.averageLatencyMs(), EPS);
    }

    @Test
    public void fps是每秒回呼次數() {
        ScanStats stats = new ScanStats();
        long t = 10_000;
        for (int i = 0; i < 20; i++) {
            stats.record(50, t + i * 50L);
        }
        stats.record(50, t + 1000);

        assertTrue("應該接近 20 fps，實際 " + stats.analysisFps(),
                stats.analysisFps() >= 19 && stats.analysisFps() <= 22);
    }

    @Test
    public void 重設之後歸零() {
        ScanStats stats = new ScanStats();
        stats.record(120, 1000);
        stats.reset();
        assertEquals(0, stats.averageLatencyMs(), EPS);
        assertEquals(0, stats.maxLatencyMs(), EPS);
    }

    @Test
    public void 格式化字串包含關鍵數字() {
        ScanStats stats = new ScanStats();
        stats.record(140, 1000);
        String text = stats.format(23);
        assertTrue(text, text.contains("fps"));
        assertTrue(text, text.contains("140"));
        assertTrue(text, text.contains("23"));
    }
}
