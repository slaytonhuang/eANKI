package com.example.eanki.ui.scan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.eanki.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 疊在相機預覽上的取字框。
 *
 * <p>SPEC 第 5 節「掃描」：即時預覽時就浮現取字框、點字選取。
 * SPEC 第 6.1 節：<b>掃描當下就查離線字典，查不到的字以磚紅外框標示。</b>
 *
 * <p>顏色語意完全照 SPEC 第 4 節：
 * <ul>
 *   <li><b>薄荷</b>＝正向、可以加入 → 字典查得到的字</li>
 *   <li><b>磚紅</b>＝需要你處理 → 字典查不到，多半是 OCR 認錯了</li>
 *   <li>中性主文字色 → 還在查</li>
 * </ul>
 */
public class TextOverlayView extends View {

    /** 一個已經換算到 View 座標系的文字框。 */
    public static final class Box {
        /** 已換算到 View 座標，畫框與命中測試用。 */
        public final RectF rect;
        /**
         * 在<b>原始照片</b>座標系裡的位置，裁來源小圖用。
         * 即時預覽模式沒有原圖可裁，這裡會是 null。
         */
        @Nullable
        public final android.graphics.Rect sourceRect;
        /** 畫面上實際看到的字，含標點。 */
        public final String text;
        /** 正規化後的字，查字典與記錄選取都用這個。 */
        public final String word;
        public LookupState state = LookupState.PENDING;
        public boolean selected;

        public Box(RectF rect, @Nullable android.graphics.Rect sourceRect,
                   String text, String word) {
            this.rect = rect;
            this.sourceRect = sourceRect;
            this.text = text;
            this.word = word;
        }
    }

    /** SPEC 第 4 節：最小觸控目標 48dp。單字框往往比這個小，命中範圍要放大。 */
    private static final float MIN_TOUCH_DP = 48f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cornerRadius;
    private float minTouchPx;
    private int colorPending;
    private int colorFound;
    private int colorNotFound;
    private int colorSelected;

    private List<Box> boxes = Collections.emptyList();

    public TextOverlayView(Context context) {
        this(context, null);
    }

    public TextOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cornerRadius = 4f * density;
        minTouchPx = MIN_TOUCH_DP * density;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        fill.setStyle(Paint.Style.FILL);

        colorPending = getContext().getColor(R.color.text_primary);
        colorFound = getContext().getColor(R.color.mint);
        colorNotFound = getContext().getColor(R.color.brick_outline);
        colorSelected = getContext().getColor(R.color.mint);
    }

    /** 更新要畫的框。必須在主執行緒呼叫。 */
    public void setBoxes(List<Box> newBoxes) {
        boxes = newBoxes == null ? Collections.<Box>emptyList() : new ArrayList<>(newBoxes);
        invalidate();
    }

    public int getBoxCount() {
        return boxes.size();
    }

    /**
     * 找出點到哪個字。
     *
     * <p>命中範圍會撐到至少 48dp（SPEC 第 4 節的最小觸控目標），
     * 因為單字框通常只有十幾 dp 高，照原尺寸幾乎點不到。
     * 有多個候選時取中心最近的，避免撐大後互相重疊造成誤選。
     */
    @Nullable
    public Box findBoxAt(float x, float y) {
        Box best = null;
        float bestDistance = Float.MAX_VALUE;

        for (Box box : boxes) {
            RectF touch = expandToMinTouch(box.rect);
            if (!touch.contains(x, y)) {
                continue;
            }
            float dx = box.rect.centerX() - x;
            float dy = box.rect.centerY() - y;
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = box;
            }
        }
        return best;
    }

    private RectF expandToMinTouch(RectF rect) {
        float padX = Math.max(0f, (minTouchPx - rect.width()) / 2f);
        float padY = Math.max(0f, (minTouchPx - rect.height()) / 2f);
        return new RectF(rect.left - padX, rect.top - padY,
                rect.right + padX, rect.bottom + padY);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        for (Box box : boxes) {
            paintFor(box);
            canvas.drawRoundRect(box.rect, cornerRadius, cornerRadius, fill);
            canvas.drawRoundRect(box.rect, cornerRadius, cornerRadius, stroke);
        }
    }

    /**
     * 已選的字畫成實心，未選但查得到的畫成半透明底 —— 讓「可以點」與「已經點了」
     * 一眼分得出來，而不是只靠顏色深淺。
     */
    private void paintFor(Box box) {
        if (box.selected) {
            fill.setColor(colorSelected);
            fill.setAlpha(210);
            stroke.setColor(colorSelected);
            stroke.setAlpha(255);
            return;
        }
        switch (box.state) {
            case FOUND:
                fill.setColor(colorFound);
                fill.setAlpha(60);
                stroke.setColor(colorFound);
                stroke.setAlpha(230);
                break;
            case NOT_FOUND:
                // SPEC 6.1：磚紅「外框」，刻意不填底，讓它看起來是待處理而不是可選
                fill.setColor(colorNotFound);
                fill.setAlpha(0);
                stroke.setColor(colorNotFound);
                stroke.setAlpha(255);
                break;
            default:
                fill.setColor(colorPending);
                fill.setAlpha(22);
                stroke.setColor(colorPending);
                stroke.setAlpha(120);
                break;
        }
    }
}
