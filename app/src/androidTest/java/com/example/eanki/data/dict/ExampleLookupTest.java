package com.example.eanki.data.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tatoeba 例句備援。SPEC 第 8 節 Phase 4。
 *
 * <p>跑的是真正的 assets 字典檔，所以同時驗了「索引真的有被建進去」。
 */
@RunWith(AndroidJUnit4.class)
public class ExampleLookupTest {

    private DictionaryRepository dict;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        dict = DictionaryRepository.get(context);
        assertTrue("字典要先就緒", dict.isReady());
    }

    @Test
    public void 常見字查得到例句() {
        for (String word : new String[]{"abandon", "account", "run", "conference"}) {
            List<DictExample> examples = dict.findExamples(word, 2);
            assertFalse(word + " 應該要有例句", examples.isEmpty());
            for (DictExample e : examples) {
                assertFalse(e.textEn.isEmpty());
                assertFalse(e.textZh.isEmpty());
            }
        }
    }

    @Test
    public void 上限會被遵守() {
        assertTrue(dict.findExamples("run", 1).size() <= 1);
        assertTrue(dict.findExamples("run", 2).size() <= 2);
        assertEquals(0, dict.findExamples("run", 0).size());
    }

    /**
     * 掃描到的是變化形也要查得到。
     *
     * <p>索引是用原形建的，使用者拍到的卻常常是 {@code abandoned}、{@code running}。
     * 沒有詞形還原的話，備援對這些字等於不存在。
     */
    @Test
    public void 變化形透過詞形還原查得到() {
        List<DictExample> direct = dict.findExamples("abandon", 2);
        List<DictExample> inflected = dict.findExamples("abandoning", 2);
        assertFalse("abandoning 應該還原成 abandon 後查到", inflected.isEmpty());
        assertEquals(direct.get(0).textEn, inflected.get(0).textEn);
    }

    /** 大小寫與前後空白不影響（跟 lookUp 走同一套正規化）。 */
    @Test
    public void 大小寫與空白不影響() {
        assertFalse(dict.findExamples("  Abandon ", 2).isEmpty());
        assertFalse(dict.findExamples("ACCOUNT", 2).isEmpty());
    }

    @Test
    public void 查不到的字回傳空清單而不是壞掉() {
        assertTrue(dict.findExamples("zzzqqxnotaword", 2).isEmpty());
        assertTrue(dict.findExamples("", 2).isEmpty());
        assertTrue(dict.findExamples(null, 2).isEmpty());
    }

    /** 同一個字的兩句例句不該是重複的。 */
    @Test
    public void 同一個字的例句不重複() {
        for (String word : new String[]{"account", "contact", "conference", "assume"}) {
            List<DictExample> examples = dict.findExamples(word, 2);
            if (examples.size() < 2) {
                continue;
            }
            assertFalse(word + " 的兩句英文重複了",
                    examples.get(0).textEn.equals(examples.get(1).textEn));
            assertFalse(word + " 的兩句中文重複了",
                    examples.get(0).textZh.equals(examples.get(1).textZh));
        }
    }

    /**
     * 例句是繁體的。
     *
     * <p>Tatoeba 的中文語料是簡體，建索引時過 OpenCC s2tw。
     * 漏掉這一步的話，一本繁體單字本上會突然冒出簡體例句。
     */
    @Test
    public void 例句已經轉成繁體() {
        String simplifiedOnly = "们说话时买卖东写读过点样开关问题实际应该经济业务电脑网络产业国际车马鸟鱼见长书广无气";
        int checked = 0;
        for (String word : new String[]{"abandon", "account", "run", "conference",
                "assume", "publish", "contact", "welfare", "survey", "parent"}) {
            for (DictExample e : dict.findExamples(word, 2)) {
                checked++;
                for (int i = 0; i < simplifiedOnly.length(); i++) {
                    assertFalse("「" + e.textZh + "」含簡體字 " + simplifiedOnly.charAt(i),
                            e.textZh.indexOf(simplifiedOnly.charAt(i)) >= 0);
                }
            }
        }
        assertTrue("應該檢查到一些例句", checked > 5);
    }

    @Test
    public void 字典狀態要回報例句數() {
        DictionaryRepository.Status status = dict.status();
        assertNotNull(status);
        assertTrue("例句數應該大於 0，代表索引有建進 assets", status.exampleCount > 0);
    }
}
