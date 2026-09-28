package com.example.eanki.review;

/**
 * SM-2 排程。<b>純 Java，不依賴 Android 或 Room</b>，可以直接跑 JVM 單元測試。
 *
 * <p>輸入是「目前排程狀態 + 這次評分 + 複習時間」，輸出是新的排程狀態。
 * 沒有任何副作用，也不讀時鐘 —— 時間由呼叫端傳進來，測試才能固定時間。
 *
 * <h2>與原始 SM-2 的差異</h2>
 *
 * 原始 SM-2 的首次間隔固定是 1 天、第二次 6 天。但 SPEC 第 6.3 節把四顆評分鍵的
 * 預估間隔訂成 <b>10 分鐘 / 1 天 / 4 天 / 10 天</b>，而且這個數字會直接顯示在按鈕下方，
 * 讓評分從「主觀感覺」變成「我希望多久後再看到它」。所以首次成功複習的間隔改由
 * 評分決定，之後才進入 SM-2 的乘法遞增。
 *
 * <p>Ease factor 的更新沿用 SM-2 原始公式
 * {@code EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))}，
 * 其中 q 見 {@link Grade#quality()}：
 * <ul>
 *   <li>「太簡單」q=5 → EF +0.10</li>
 *   <li>「記得」 q=4 → EF 不變</li>
 *   <li>「有點難」q=3 → EF −0.14</li>
 * </ul>
 * 「忘了」不套用這條公式（原始公式會一次扣掉 0.54，太重），改用固定 −0.20。
 */
public final class Sm2Scheduler {

    public static final int MINUTES_PER_DAY = 24 * 60;

    public static final double DEFAULT_EASE_FACTOR = 2.5d;
    /** EF 下限。低於這個值間隔會幾乎不成長，卡片等於卡死在佇列裡。 */
    public static final double MIN_EASE_FACTOR = 1.3d;

    /** 「忘了」之後的重學間隔：10 分鐘（SPEC 第 6.3 節）。 */
    public static final int LAPSE_INTERVAL_MINUTES = 10;

    /** 首次成功複習的間隔，直接對應 SPEC 第 6.3 節的表。 */
    static final int FIRST_INTERVAL_HARD = 1 * MINUTES_PER_DAY;
    static final int FIRST_INTERVAL_GOOD = 4 * MINUTES_PER_DAY;
    static final int FIRST_INTERVAL_EASY = 10 * MINUTES_PER_DAY;

    /** 「有點難」不用 EF 而用固定的小倍率，否則 EF 高的卡答「有點難」反而跳很遠。 */
    static final double HARD_MULTIPLIER = 1.2d;
    /** 「太簡單」在 EF 之上再加成。 */
    static final double EASY_BONUS = 1.3d;

    static final double LAPSE_EASE_PENALTY = 0.2d;

    /** 間隔上限一年。再長對背單字沒有意義，而且會讓 due_date 的算術溢位風險變高。 */
    static final int MAX_INTERVAL_MINUTES = 365 * MINUTES_PER_DAY;

    /**
     * 算出這次評分之後的新排程。
     *
     * @param current    目前狀態，新卡傳 {@link Schedule#newCard(long)}
     * @param grade      這次的評分
     * @param reviewedAt 複習發生的時間，epoch millis
     */
    public static Schedule next(Schedule current, Grade grade, long reviewedAt) {
        if (current == null || grade == null) {
            throw new IllegalArgumentException("current 與 grade 不可為 null");
        }

        if (grade.isLapse()) {
            return lapse(current, reviewedAt);
        }
        return success(current, grade, reviewedAt);
    }

    /**
     * 「忘了」：重置 repetitions、間隔退回 10 分鐘、lapses +1、EF 扣 0.2。
     *
     * <p>repetitions 歸零是關鍵 —— 下次答對時會重新走「首次成功」那條路徑，
     * 拿到 1/4/10 天而不是接著原本已經拉很長的間隔繼續乘。
     */
    private static Schedule lapse(Schedule current, long reviewedAt) {
        double ease = clampEase(current.easeFactor - LAPSE_EASE_PENALTY);
        int interval = LAPSE_INTERVAL_MINUTES;
        return new Schedule(
                interval,
                ease,
                0,
                current.lapses + 1,
                dueAfter(reviewedAt, interval),
                reviewedAt);
    }

    private static Schedule success(Schedule current, Grade grade, long reviewedAt) {
        double ease = clampEase(current.easeFactor + easeDelta(grade));
        int repetitions = current.repetitions + 1;

        final int interval;
        if (repetitions == 1) {
            // 首次成功（含遺忘後重新學起來），間隔直接由評分決定
            interval = firstInterval(grade);
        } else {
            interval = grow(current.intervalMinutes, ease, grade);
        }

        return new Schedule(
                interval,
                ease,
                repetitions,
                current.lapses,
                dueAfter(reviewedAt, interval),
                reviewedAt);
    }

    private static int firstInterval(Grade grade) {
        switch (grade) {
            case HARD:
                return FIRST_INTERVAL_HARD;
            case GOOD:
                return FIRST_INTERVAL_GOOD;
            case EASY:
                return FIRST_INTERVAL_EASY;
            default:
                throw new IllegalStateException("firstInterval 不該收到 " + grade);
        }
    }

    private static int grow(int previousMinutes, double ease, Grade grade) {
        final double multiplier;
        switch (grade) {
            case HARD:
                multiplier = HARD_MULTIPLIER;
                break;
            case GOOD:
                multiplier = ease;
                break;
            case EASY:
                multiplier = ease * EASY_BONUS;
                break;
            default:
                throw new IllegalStateException("grow 不該收到 " + grade);
        }

        long grown = Math.round(previousMinutes * multiplier);
        // 至少要比上次長一分鐘，否則 EF 觸底時間隔會停滯甚至倒退
        if (grown <= previousMinutes) {
            grown = previousMinutes + 1L;
        }
        return (int) Math.min(grown, MAX_INTERVAL_MINUTES);
    }

    /** SM-2 原始公式：EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))。 */
    private static double easeDelta(Grade grade) {
        int diff = 5 - grade.quality();
        return 0.1d - diff * (0.08d + diff * 0.02d);
    }

    private static double clampEase(double ease) {
        return Math.max(MIN_EASE_FACTOR, ease);
    }

    private static long dueAfter(long reviewedAt, int intervalMinutes) {
        return reviewedAt + intervalMinutes * 60_000L;
    }

    private Sm2Scheduler() {
    }
}
