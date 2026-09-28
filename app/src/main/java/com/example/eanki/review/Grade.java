package com.example.eanki.review;

/**
 * 四顆評分鍵。SPEC 第 6.3 節，由左到右負向到正向。
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <p>{@link #code()} 是存進 {@code review_state.grade_1/2/3} 的值。
 * <b>刻意用明寫的 code 而不是 {@code ordinal()}</b>：
 * 之後若在中間插入新的評分等級，ordinal 會整批錯位，已存的資料就全錯了。
 */
public enum Grade {

    /** 忘了。等同 SM-2 的 lapse，會重置 repetitions。 */
    FORGOT(0, 1),
    /** 有點難。答對了，但很吃力。 */
    HARD(1, 3),
    /** 記得。 */
    GOOD(2, 4),
    /** 太簡單。 */
    EASY(3, 5);

    private final int code;
    private final int quality;

    Grade(int code, int quality) {
        this.code = code;
        this.quality = quality;
    }

    /** 存進資料庫的值。 */
    public int code() {
        return code;
    }

    /**
     * 對應到 SM-2 原始論文的 quality（0–5），用於 ease factor 的計算。
     * SM-2 認定 q &lt; 3 為失敗，所以「忘了」給 1，其餘三個給 3 / 4 / 5。
     */
    public int quality() {
        return quality;
    }

    /** 是否為一次遺忘。 */
    public boolean isLapse() {
        return this == FORGOT;
    }

    public static Grade fromCode(int code) {
        for (Grade g : values()) {
            if (g.code == code) {
                return g;
            }
        }
        throw new IllegalArgumentException("未知的 grade code：" + code);
    }

    /** 兩個評分中比較差的那個。SPEC 第 6.5 節「平手時取較差的」會用到。 */
    public static Grade worse(Grade a, Grade b) {
        return a.code <= b.code ? a : b;
    }
}
