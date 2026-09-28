package com.example.eanki.data.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 掃描來源照片的存放。SPEC 第 3 節 {@code word_entry.image_uri}、
 * 第 5 節複習背面的「來源照片 chip」。
 *
 * <h2>只存局部小圖</h2>
 *
 * 整張照片存下來很快就會吃掉幾百 MB。實際有用的是「這個字出現在哪一行」，
 * 所以只裁單字周圍一小塊，並且往兩側多帶一點上下文 ——
 * 只框住單字本身的話，複習時看到一張孤零零的 "dictionary" 沒有任何回憶價值。
 *
 * <h2>資料庫只存檔名，不存絕對路徑</h2>
 *
 * app 的私有目錄路徑會因為重新安裝、使用者切換、備份還原而改變。
 * 存絕對路徑的話，SPEC 第 8 節 Phase 4 要做的「備份 / 還原」一還原就全是死連結。
 *
 * <h2>孤兒檔案</h2>
 *
 * 刪掉單字時<b>不會立刻刪圖</b>：單字本的刪除有「復原」，
 * 立刻刪圖的話復原回來的那筆就指向一個不存在的檔案。
 * 改成 App 啟動時掃一次，把沒有任何 word_entry 指向的檔案清掉 ——
 * 這同時也能收拾當機或寫到一半失敗留下的殘檔。
 */
public final class ScanImageStore {

    private static final String TAG = "ScanImageStore";
    private static final String DIR = "scan_images";

    /** 裁切時往外擴的比例，以單字框高度為單位。 */
    private static final float PAD_X_RATIO = 2.0f;
    private static final float PAD_Y_RATIO = 0.9f;

    /** 存檔前縮到最長邊不超過這個尺寸。複習卡片上的縮圖用不到更大。 */
    private static final int MAX_EDGE = 640;
    private static final int JPEG_QUALITY = 80;

    private final File dir;

    public ScanImageStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), DIR));
    }

    /**
     * <b>測試專用</b>：指定自己的目錄。
     * 測試對正式目錄做孤兒清理會刪掉使用者真正的來源照片。
     */
    @VisibleForTesting
    public ScanImageStore(File directory) {
        this.dir = directory;
    }

    /**
     * 從整張凍結圖裁出單字周圍的小圖並存成 JPEG。
     *
     * @param source    凍結的整張照片
     * @param wordRect  單字在 {@code source} 座標系裡的位置
     * @return 檔名（不是路徑），存進 {@code word_entry.image_uri}；失敗回傳 null
     */
    @WorkerThread
    @Nullable
    public String save(@NonNull Bitmap source, @NonNull Rect wordRect) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "建不出目錄 " + dir);
            return null;
        }

        Rect crop = padded(wordRect, source.getWidth(), source.getHeight());
        if (crop.width() <= 0 || crop.height() <= 0) {
            return null;
        }

        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            cropped = Bitmap.createBitmap(
                    source, crop.left, crop.top, crop.width(), crop.height());
            scaled = downscale(cropped);

            String name = "scan_" + UUID.randomUUID() + ".jpg";
            File file = new File(dir, name);
            try (OutputStream out = new FileOutputStream(file)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    Log.e(TAG, "壓縮失敗");
                    file.delete();
                    return null;
                }
            }
            return name;
        } catch (Exception e) {
            Log.e(TAG, "存圖失敗", e);
            return null;
        } finally {
            if (scaled != null && scaled != cropped) {
                scaled.recycle();
            }
            if (cropped != null) {
                cropped.recycle();
            }
        }
    }

    /** 往兩側多帶一點上下文，並夾在圖片範圍內。 */
    private static Rect padded(Rect word, int maxWidth, int maxHeight) {
        int padX = Math.round(word.height() * PAD_X_RATIO);
        int padY = Math.round(word.height() * PAD_Y_RATIO);
        return new Rect(
                Math.max(0, word.left - padX),
                Math.max(0, word.top - padY),
                Math.min(maxWidth, word.right + padX),
                Math.min(maxHeight, word.bottom + padY));
    }

    private static Bitmap downscale(Bitmap bitmap) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= MAX_EDGE) {
            return bitmap;
        }
        float ratio = MAX_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                Math.max(1, Math.round(bitmap.getHeight() * ratio)),
                true);
    }

    /** 檔名還原成實際檔案。顯示來源照片時用。 */
    @Nullable
    public File resolve(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        File file = new File(dir, name);
        return file.exists() ? file : null;
    }

    /**
     * 讀出來源照片並縮到指定寬度以內。
     *
     * <p>掃描裁切下來的圖本來就只有一個單字那麼大，但使用者可能拍的是
     * 高解析度的整頁再裁 —— 不設上限就等於把一張未知大小的 bitmap
     * 直接塞進複習卡片。先讀 bounds 算出 {@code inSampleSize} 再解碼。
     *
     * @return 解不開就回傳 null（檔案可能在還原備份後缺席）
     */
    @WorkerThread
    @Nullable
    public static Bitmap decodeScaled(@Nullable File file, int maxWidthPx) {
        if (file == null || !file.exists() || maxWidthPx <= 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxWidthPx) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) {
            Log.w(TAG, "來源照片解不開：" + file.getName(), e);
            return null;
        }
    }

    /**
     * 檔名對應到的目標檔案，<b>不管它存不存在</b>。
     *
     * <p>{@link #resolve} 只回傳已存在的檔案，還原時需要的正好相反：
     * 要一個「準備寫進去」的位置。
     */
    @Nullable
    public File fileFor(@Nullable String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
            // 備份檔可能被手動改過，檔名帶路徑就會寫到私有目錄外面
            return null;
        }
        return new File(dir, name);
    }

    /**
     * 清掉沒有任何單字指向的圖檔。
     *
     * @param referenced 目前所有 {@code word_entry.image_uri} 的值
     * @return 刪掉幾個檔案
     */
    @WorkerThread
    public int sweepOrphans(@NonNull Collection<String> referenced) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        Set<String> keep = new HashSet<>(referenced);
        int removed = 0;
        for (File file : files) {
            if (!keep.contains(file.getName()) && file.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            Log.i(TAG, "清掉 " + removed + " 個沒有單字指向的圖檔");
        }
        return removed;
    }
}
