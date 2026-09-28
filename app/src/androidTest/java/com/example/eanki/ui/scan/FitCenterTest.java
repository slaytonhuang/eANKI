package com.example.eanki.ui.scan;

import static org.junit.Assert.assertEquals;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * fitCenter 的座標轉換。
 *
 * <p>這段之所以值得測：框畫錯位置是「看得到但很難說清楚錯在哪」的 bug，
 * 實測時就踩過一次（讀到還沒 layout 的 ImageView 矩陣，整批框飛到左上角）。
 */
@RunWith(AndroidJUnit4.class)
public class FitCenterTest {

    private static final float EPS = 0.01f;

    private static RectF map(Matrix m, float l, float t, float r, float b) {
        RectF rect = new RectF(l, t, r, b);
        m.mapRect(rect);
        return rect;
    }

    /** 圖比容器瘦：以寬度為準縮放，上下留黑邊。 */
    @Test
    public void 高瘦的圖以寬度為準並垂直置中() {
        // 1545x2000 的圖放進 904x2316 的容器
        Matrix m = FitCenter.matrix(1545, 2000, 904, 2316);
        float scale = 904f / 1545f;                 // 0.5851
        float scaledHeight = 2000 * scale;          // 1170.2
        float expectedTop = (2316 - scaledHeight) / 2f;

        RectF whole = map(m, 0, 0, 1545, 2000);
        assertEquals(0f, whole.left, EPS);
        assertEquals(904f, whole.right, EPS);
        assertEquals(expectedTop, whole.top, EPS);
        assertEquals(expectedTop + scaledHeight, whole.bottom, EPS);
    }

    /** 圖比容器胖：以高度為準，左右留黑邊。 */
    @Test
    public void 寬扁的圖以高度為準並水平置中() {
        Matrix m = FitCenter.matrix(4000, 1000, 800, 800);
        RectF whole = map(m, 0, 0, 4000, 1000);

        assertEquals(800f, whole.width(), EPS);
        assertEquals(200f, whole.height(), EPS);
        assertEquals(0f, whole.left, EPS);
        assertEquals(300f, whole.top, EPS);          // (800-200)/2
    }

    @Test
    public void 圖與容器同比例時填滿且不位移() {
        Matrix m = FitCenter.matrix(1000, 2000, 500, 1000);
        RectF whole = map(m, 0, 0, 1000, 2000);

        assertEquals(0f, whole.left, EPS);
        assertEquals(0f, whole.top, EPS);
        assertEquals(500f, whole.right, EPS);
        assertEquals(1000f, whole.bottom, EPS);
    }

    /** 圖上某個字的框要落在畫面的對應位置。 */
    @Test
    public void 單字框換算到正確的畫面位置() {
        Matrix m = FitCenter.matrix(1000, 2000, 500, 2000);
        // 縮放 0.5，垂直置中偏移 (2000-1000)/2 = 500
        RectF word = map(m, 100, 200, 300, 260);

        assertEquals(50f, word.left, EPS);
        assertEquals(500 + 100f, word.top, EPS);
        assertEquals(150f, word.right, EPS);
        assertEquals(500 + 130f, word.bottom, EPS);
    }

    /** 尺寸還沒定的時候回傳單位矩陣，不要亂算出一堆錯位的框。 */
    @Test
    public void 尺寸為零時回傳單位矩陣() {
        RectF unchanged = map(FitCenter.matrix(0, 0, 500, 500), 10, 20, 30, 40);
        assertEquals(10f, unchanged.left, EPS);
        assertEquals(40f, unchanged.bottom, EPS);

        RectF alsoUnchanged = map(FitCenter.matrix(100, 100, 0, 0), 10, 20, 30, 40);
        assertEquals(10f, alsoUnchanged.left, EPS);
    }
}
