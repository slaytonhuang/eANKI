package com.example.eanki.ui.familiarity;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.eanki.R;
import com.example.eanki.review.Grade;

/**
 * 四級熟悉度的色票與名稱，<b>全 App 唯一一份</b>。
 *
 * <p>SPEC 第 4 節規定磚紅／琥珀／薄荷的語意固定，色票要全 App 一致。
 * 熟悉度統計、堆疊條、篩選清單的評分小點都走這裡，
 * 任何一個畫面都不該自己挑顏色 —— 一旦有畫面做變體，下個用到四級顏色的地方就對不上。
 *
 * <p>堆疊條上「記得」{@code #6FB49D} 與「太簡單」{@code #7FBFA9} 兩色很接近，
 * 解法是在段與段之間加 2dp 間隔（{@code stack_bar_gap}），不是改色。
 */
public final class LevelPalette {

    /** {@code null} 代表還沒有這一次的評分，用中性灰。 */
    @ColorRes
    public static int color(@Nullable Grade level) {
        if (level == null) {
            return R.color.border;
        }
        switch (level) {
            case FORGOT:
                return R.color.brick_outline;
            case HARD:
                return R.color.amber;
            case GOOD:
                return R.color.mint_2;
            case EASY:
            default:
                return R.color.mint;
        }
    }

    @StringRes
    public static int name(Grade level) {
        switch (level) {
            case FORGOT:
                return R.string.grade_forgot;
            case HARD:
                return R.string.grade_hard;
            case GOOD:
                return R.string.grade_good;
            case EASY:
            default:
                return R.string.grade_easy;
        }
    }

    private LevelPalette() {
    }
}
