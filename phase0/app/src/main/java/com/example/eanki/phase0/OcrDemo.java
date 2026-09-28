package com.example.eanki.phase0;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo 1：用 ML Kit Text Recognition v2 辨識 assets 裡的一張圖，
 * 印出每個 element（= 一個單字）的文字內容與 bounding box。
 *
 * <p>Phase 3 真正要做的是 CameraX 拍照後辨識，但座標的意義完全相同：
 * boundingBox 是「影像像素座標」，不是 View 座標，疊加方框時必須自己換算。
 */
final class OcrDemo {

    interface Callback {
        void onLog(String line);

        void onFinished(int wordCount);

        void onError(String message);
    }

    private static final String ASSET_NAME = "sample_text.jpg";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void run(Context context, Callback callback) {
        io.execute(() -> {
            Bitmap bitmap;
            try (InputStream in = context.getAssets().open(ASSET_NAME)) {
                bitmap = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                main.post(() -> callback.onError("讀不到 assets/" + ASSET_NAME + "：" + e));
                return;
            }
            if (bitmap == null) {
                main.post(() -> callback.onError("assets/" + ASSET_NAME + " 解碼失敗"));
                return;
            }

            // 透明底的圖（例如從 PDF 轉出的 PNG、去背的截圖）在轉成灰階時
            // 透明像素會變成黑色，黑字就整片糊掉，ML Kit 會一個字都認不到。
            // Phase 3 讀相簿也會踩到，所以統一先壓白底。
            if (bitmap.hasAlpha()) {
                bitmap = flattenOntoWhite(bitmap);
                main.post(() -> callback.onLog("圖片含 alpha 通道，已壓上白底再辨識"));
            }

            final Bitmap ready = bitmap;
            main.post(() -> recognize(ready, callback));
        });
    }

    private static Bitmap flattenOntoWhite(Bitmap source) {
        Bitmap flat = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return flat;
    }

    private void recognize(Bitmap bitmap, Callback callback) {
        callback.onLog(String.format(Locale.US,
                "圖片尺寸 %d x %d px（bounding box 都以這個座標系為準）",
                bitmap.getWidth(), bitmap.getHeight()));

        // rotationDegrees = 0，因為 assets 的圖沒有 EXIF 旋轉。
        // Phase 3 從 CameraX 來的影像要把 ImageProxy 的 rotationDegrees 帶進來。
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        long startedAt = System.currentTimeMillis();
        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    int count = dump(text, callback);
                    callback.onLog(String.format(Locale.US,
                            "辨識完成：%d 個 block，%d 個單字，耗時 %d ms",
                            text.getTextBlocks().size(), count, elapsed));
                    callback.onFinished(count);
                    recognizer.close();
                })
                .addOnFailureListener(e -> {
                    callback.onError("辨識失敗：" + e);
                    recognizer.close();
                });
    }

    /** 走訪 block → line → element，把每個單字與座標印出來。回傳單字總數。 */
    private int dump(Text text, Callback callback) {
        int wordCount = 0;
        int blockIndex = 0;

        for (Text.TextBlock block : text.getTextBlocks()) {
            callback.onLog(String.format(Locale.US,
                    "[block %d] lang=%s rect=%s",
                    blockIndex++,
                    block.getRecognizedLanguage(),
                    rectToString(block.getBoundingBox())));

            for (Text.Line line : block.getLines()) {
                callback.onLog("  [line] \"" + line.getText() + "\"");

                for (Text.Element element : line.getElements()) {
                    wordCount++;
                    callback.onLog(String.format(Locale.US,
                            "    \"%s\"  box=%s  corners=%s",
                            element.getText(),
                            rectToString(element.getBoundingBox()),
                            cornersToString(element.getCornerPoints())));
                }
            }
        }
        return wordCount;
    }

    private static String rectToString(Rect r) {
        if (r == null) {
            return "null";
        }
        return String.format(Locale.US, "(l=%d, t=%d, r=%d, b=%d, w=%d, h=%d)",
                r.left, r.top, r.right, r.bottom, r.width(), r.height());
    }

    /**
     * cornerPoints 是四個角（左上順時針）。文字若在照片裡是斜的，
     * boundingBox 只是外接矩形，cornerPoints 才貼合文字 —— Phase 3 疊加方框要用這個。
     */
    private static String cornersToString(Point[] points) {
        if (points == null || points.length == 0) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('(').append(p.x).append(',').append(p.y).append(')');
        }
        return sb.toString();
    }

    void shutdown() {
        io.shutdown();
    }
}
