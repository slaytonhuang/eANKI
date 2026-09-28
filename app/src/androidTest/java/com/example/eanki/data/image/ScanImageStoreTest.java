package com.example.eanki.data.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * 掃描來源小圖的存放與孤兒清理。
 * 需要真的 Bitmap 與檔案系統，所以跑 instrumented test。
 */
@RunWith(AndroidJUnit4.class)
public class ScanImageStoreTest {

    private ScanImageStore store;
    private File dir;

    /**
     * <b>用 cache 底下的獨立目錄，不碰正式的 scan_images。</b>
     * 孤兒清理會刪檔，指到正式目錄就會刪掉使用者真正的來源照片。
     */
    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "test_scan_images");
        store = new ScanImageStore(dir);
        store.sweepOrphans(Collections.<String>emptyList());
    }

    private static Bitmap page(int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        return bitmap;
    }

    @Test
    public void 存檔會產生實際的檔案() {
        Bitmap source = page(1440, 1920);
        String name = store.save(source, new Rect(400, 600, 560, 640));

        assertNotNull(name);
        assertTrue(name.endsWith(".jpg"));
        File file = store.resolve(name);
        assertNotNull(file);
        assertTrue("檔案要有內容", file.length() > 0);
    }

    /** 只裁單字周圍，不是整張。整張存下來很快會吃掉幾百 MB。 */
    @Test
    public void 存的是局部小圖而不是整張() {
        Bitmap source = page(1440, 1920);
        String name = store.save(source, new Rect(400, 600, 560, 640));

        File file = store.resolve(name);
        Bitmap saved = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());

        assertTrue("寬度要遠小於原圖", saved.getWidth() < source.getWidth() / 2);
        assertTrue("高度要遠小於原圖", saved.getHeight() < source.getHeight() / 2);
        saved.recycle();
    }

    /** 只框住單字本身的話，複習時看到孤零零一個字沒有回憶價值。 */
    @Test
    public void 裁切會往外帶一點上下文() {
        Bitmap source = page(1440, 1920);
        Rect word = new Rect(400, 600, 560, 640);   // 160 x 40
        String name = store.save(source, word);

        Bitmap saved = android.graphics.BitmapFactory.decodeFile(
                store.resolve(name).getAbsolutePath());
        assertTrue("要比單字本身寬", saved.getWidth() > word.width());
        assertTrue("要比單字本身高", saved.getHeight() > word.height());
        saved.recycle();
    }

    /** 字在圖片邊緣時，往外擴不可以超出圖片範圍。 */
    @Test
    public void 邊緣的字不會裁到圖片外() {
        Bitmap source = page(400, 400);
        String name = store.save(source, new Rect(0, 0, 60, 20));

        assertNotNull("貼在左上角也要能存", name);
        assertNotNull(store.resolve(name));
    }

    @Test
    public void 每次存檔的檔名都不同() {
        Bitmap source = page(800, 600);
        String a = store.save(source, new Rect(100, 100, 200, 130));
        String b = store.save(source, new Rect(100, 100, 200, 130));

        assertNotNull(a);
        assertNotNull(b);
        assertFalse("同樣的裁切也不能互相覆蓋", a.equals(b));
    }

    // ---- 孤兒清理 ----

    @Test
    public void 沒有單字指向的檔案會被清掉() {
        Bitmap source = page(800, 600);
        String keep = store.save(source, new Rect(10, 10, 110, 40));
        String orphan = store.save(source, new Rect(10, 10, 110, 40));

        int removed = store.sweepOrphans(Collections.singletonList(keep));

        assertEquals(1, removed);
        assertNotNull("還被指向的要留著", store.resolve(keep));
        assertNull("沒人指向的要刪掉", store.resolve(orphan));
    }

    @Test
    public void 全部都還被指向時不刪任何東西() {
        Bitmap source = page(800, 600);
        String a = store.save(source, new Rect(10, 10, 110, 40));
        String b = store.save(source, new Rect(10, 10, 110, 40));

        assertEquals(0, store.sweepOrphans(Arrays.asList(a, b)));
        assertNotNull(store.resolve(a));
        assertNotNull(store.resolve(b));
    }

    @Test
    public void 清理可以重複執行() {
        Bitmap source = page(800, 600);
        store.save(source, new Rect(10, 10, 110, 40));

        assertEquals(1, store.sweepOrphans(Collections.<String>emptyList()));
        assertEquals("第二次沒東西可刪", 0,
                store.sweepOrphans(Collections.<String>emptyList()));
    }

    @Test
    public void 檔名為空時resolve回傳null() {
        assertNull(store.resolve(null));
        assertNull(store.resolve(""));
        assertNull(store.resolve("不存在的檔案.jpg"));
    }
}
