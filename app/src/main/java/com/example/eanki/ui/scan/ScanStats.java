package com.example.eanki.ui.scan;

import java.util.Locale;

/**
 * 辨識效能的統計。這一批的重點之一就是量出「即時辨識會不會拖垮預覽」，
 * 所以把數字算出來顯示在畫面上，而不是憑感覺。
 *
 * <p>純 Java，沒有 Android 相依，可以單獨測試。
 */
public final class ScanStats {

    /** 取最近這麼多幀做移動平均，太短會跳動、太長反應不出卡頓。 */
    static final int WINDOW = 20;

    private final long[] latencies = new long[WINDOW];
    private int count;
    private int index;

    private long windowStartAt;
    private int framesInWindow;
    private double analysisFps;

    /** 記錄一次辨識：{@code latencyMs} 是這一幀從進來到辨識完的耗時。 */
    public void record(long latencyMs, long now) {
        latencies[index] = latencyMs;
        index = (index + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }

        if (windowStartAt == 0L) {
            windowStartAt = now;
            framesInWindow = 0;
        }
        framesInWindow++;

        long elapsed = now - windowStartAt;
        if (elapsed >= 1000L) {
            analysisFps = framesInWindow * 1000.0 / elapsed;
            windowStartAt = now;
            framesInWindow = 0;
        }
    }

    /** 最近數十幀的平均辨識耗時，毫秒。 */
    public double averageLatencyMs() {
        if (count == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += latencies[i];
        }
        return sum / (double) count;
    }

    public double maxLatencyMs() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, latencies[i]);
        }
        return max;
    }

    /** 實際跑完辨識的幀率。預覽本身是另一條串流，不受這個數字限制。 */
    public double analysisFps() {
        return analysisFps;
    }

    public String format(int boxCount) {
        return String.format(Locale.US,
                "辨識 %.1f fps · 平均 %.0f ms · 最久 %.0f ms · %d 個字",
                analysisFps, averageLatencyMs(), maxLatencyMs(), boxCount);
    }

    public void reset() {
        count = 0;
        index = 0;
        windowStartAt = 0L;
        framesInWindow = 0;
        analysisFps = 0;
    }
}
