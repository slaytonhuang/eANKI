package com.example.eanki.data.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * 來源照片 chip 背後的檔案存取。SPEC 第 5 節「複習背面：來源照片 chip」。
 *
 * <p>用測試專用的目錄，<b>不碰使用者實際的掃描圖</b>。
 */
@RunWith(AndroidJUnit4.class)
public class SourcePhotoTest {

    private File dir;
    private ScanImageStore store;

    @Before
    public void setUp() {
        dir = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "test_source_photo");
        store = new ScanImageStore(dir);
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private String saveSample(int width, int height) {
        Bitmap page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        page.eraseColor(0xFF3366CC);
        return store.save(page, new Rect(0, 0, width, height));
    }

    @Test
    public void 存下來的圖讀得回來() {
        String name = saveSample(400, 120);
        assertNotNull(name);
        File file = store.resolve(name);
        assertNotNull("剛存的圖應該找得到", file);
        assertTrue(file.length() > 0);
    }

    /**
     * 檔案不在時要回 null，不是回一個指向不存在檔案的 File。
     *
     * <p>還原備份時圖檔是在交易外寫的，也可能被孤兒清理收掉 ——
     * 畫面靠這個判斷「要不要顯示 chip」。顯示一個點了沒反應的 chip 比不顯示糟。
     */
    @Test
    public void 檔案不在時回傳null() {
        assertNull(store.resolve("nonexistent.jpg"));
        assertNull(store.resolve(null));
        assertNull(store.resolve(""));
    }

    @Test
    public void 解碼會縮到指定寬度以內() {
        String name = saveSample(1600, 400);
        File file = store.resolve(name);

        Bitmap small = ScanImageStore.decodeScaled(file, 200);
        assertNotNull(small);
        assertTrue("解出來 " + small.getWidth() + " px，應該在 400 以內",
                small.getWidth() <= 400);

        Bitmap full = ScanImageStore.decodeScaled(file, 4000);
        assertNotNull(full);
        assertTrue("上限放寬時不該被縮得更小", full.getWidth() >= small.getWidth());
    }

    @Test
    public void 解不開的檔案回傳null而不是丟例外() {
        assertNull(ScanImageStore.decodeScaled(null, 200));
        assertNull(ScanImageStore.decodeScaled(new File(dir, "missing.jpg"), 200));
        assertNull("寬度 0 沒有意義", ScanImageStore.decodeScaled(
                store.resolve(saveSample(100, 100)), 0));
    }

    /**
     * 備份檔可能被手動改過，檔名帶路徑就會寫到私有目錄外面。
     */
    @Test
    public void 還原時的檔名不可以帶路徑() {
        assertNull(store.fileFor("../../evil.jpg"));
        assertNull(store.fileFor("sub/dir.jpg"));
        assertNull(store.fileFor(null));
        assertNotNull(store.fileFor("ok.jpg"));
    }

    @Test
    public void 孤兒清理只留下還被指向的圖() {
        String keep = saveSample(100, 40);
        String drop = saveSample(120, 40);
        assertEquals(1, store.sweepOrphans(java.util.Collections.singletonList(keep)));
        assertNotNull(store.resolve(keep));
        assertNull(store.resolve(drop));
    }
}
