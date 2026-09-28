package com.example.eanki.ui.scan;

import android.graphics.Matrix;

/**
 * 複製 {@code ImageView.ScaleType.FIT_CENTER} 的座標轉換。
 *
 * <p>純算術，沒有 View 相依，可以單獨測試。
 *
 * <p><b>為什麼不直接用 {@code ImageView.getImageMatrix()}：</b>
 * 那個矩陣要等 View 被 layout 過才算得出來。凍結畫面的流程是
 * 「設圖 → 算框 → 顯示」，讀矩陣的當下 ImageView 還是 {@code GONE}，
 * 拿到的是單位矩陣 —— 框會整批畫在左上角而不是貼在字上。
 * 實測時相機路徑「看起來正常」只是因為前一次凍結已經 layout 過、
 * 而且兩次的圖尺寸剛好一樣；換成相簿的圖（尺寸不同）就露餡了。
 *
 * <p>自己算就完全沒有時序相依：只要知道圖與容器的尺寸就得到答案。
 */
public final class FitCenter {

    /**
     * 算出「bitmap 座標 → view 座標」的轉換。
     *
     * @param bitmapWidth  原圖寬
     * @param bitmapHeight 原圖高
     * @param viewWidth    容器寬
     * @param viewHeight   容器高
     */
    public static Matrix matrix(int bitmapWidth, int bitmapHeight,
                                int viewWidth, int viewHeight) {
        Matrix matrix = new Matrix();
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return matrix;   // 尺寸還沒定，回傳單位矩陣總比亂算好
        }
        // FIT_CENTER：等比縮到「整張都放得下」，再置中
        float scale = Math.min(viewWidth / (float) bitmapWidth,
                viewHeight / (float) bitmapHeight);
        float dx = (viewWidth - bitmapWidth * scale) / 2f;
        float dy = (viewHeight - bitmapHeight * scale) / 2f;

        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        return matrix;
    }

    private FitCenter() {
    }
}
