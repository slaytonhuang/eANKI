package com.example.eanki.data.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 詞形還原查詢。跑在實機上，用的是真正打包進 APK 的那份字典。
 *
 * <p>ECDICT 的 {@code exchange} 欄位存在<b>原形</b>那一列上，列出它的各種變化形
 * （{@code jump → p:jumped/i:jumping/3:jumps}），而變化形本身多半沒有獨立詞條。
 * 建置時（{@code tools/build_forms.py}）已經反向展開成 {@code word_form} 表，
 * 這組測試驗的是那張表加上兩段式查詢確實把查得到率補起來了。
 */
@RunWith(AndroidJUnit4.class)
public class InflectionLookupTest {

    private DictionaryRepository dictionary;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        dictionary = DictionaryRepository.get(context);
        assertTrue("字典要先就緒", dictionary.isReady());
    }

    private void assertInflection(String form, String expectedLemma) {
        DictEntry entry = dictionary.lookUp(form);
        assertNotNull(form + " 應該查得到", entry);
        assertEquals(form + " 的原形應該是 " + expectedLemma, expectedLemma, entry.word);
        assertTrue(form + " 應該標示為詞形還原", entry.viaInflection());
        assertEquals("顯示給使用者的要是他查的形式", form, entry.displayWord());
        assertNotNull(form + " 應該帶出釋義", entry.translation);
    }

    // ---- 各種詞形變化 ----

    @Test
    public void 複數() {
        assertInflection("jumps", "jump");
        assertInflection("mice", "mouse");
    }

    @Test
    public void 過去式() {
        assertInflection("walked", "walk");
        assertInflection("went", "go");
        assertInflection("ran", "run");
    }

    /**
     * 進行式。
     *
     * <p>注意常用字的 -ing 形式（{@code jumping}、{@code walking}）
     * <b>本身就是字典詞條</b>、有自己的詞頻，直接查就命中，不會走還原路徑。
     * 需要還原的是較冷門的動詞。
     */
    @Test
    public void 進行式() {
        assertInflection("abominating", "abominate");
        assertInflection("agglomerating", "agglomerate");
    }

    /** 常用字的變化形自己就是詞條時，直接命中也算「查得到」。 */
    @Test
    public void 常用的進行式本身就是詞條() {
        for (String word : new String[]{"jumping", "walking", "running"}) {
            DictEntry entry = dictionary.lookUp(word);
            assertNotNull(word + " 應該查得到", entry);
            assertNotNull(word + " 應該有釋義", entry.translation);
            assertFalse(word + " 本身是詞條，不該走還原", entry.viaInflection());
        }
    }

    @Test
    public void 比較級與最高級() {
        assertInflection("bigger", "big");
        assertInflection("biggest", "big");
        assertInflection("happier", "happy");
    }

    @Test
    public void 第三人稱單數() {
        assertInflection("walks", "walk");
        assertInflection("goes", "go");
    }

    // ---- 邊界與正確性 ----

    /** 原形本來就查得到，不該被標成詞形還原。 */
    @Test
    public void 原形直接命中且不標記還原() {
        DictEntry entry = dictionary.lookUp("jump");
        assertNotNull(entry);
        assertEquals("jump", entry.word);
        assertFalse(entry.viaInflection());
        assertNull(entry.matchedForm);
        assertEquals("jump", entry.displayWord());
    }

    /**
     * 有些字既是自己的詞條、又是別的字的變化形（{@code saw} 是鋸子，也是 see 的過去式）。
     * <b>直接命中一定優先</b>，否則查 saw 會得到「看見」而不是「鋸子」。
     */
    @Test
    public void 直接命中優先於詞形還原() {
        DictEntry entry = dictionary.lookUp("saw");
        assertNotNull(entry);
        assertEquals("saw", entry.word);
        assertFalse("saw 本身就是詞條，不該被還原成 see", entry.viaInflection());
    }

    /** 大小寫與標點照樣要能還原 —— OCR 會把標點黏在字上、也會改大小寫。 */
    @Test
    public void 大小寫與標點不影響還原() {
        DictEntry upper = dictionary.lookUp("Jumps");
        assertNotNull(upper);
        assertEquals("jump", upper.word);

        DictEntry punctuated = dictionary.lookUp("jumps,");
        assertNotNull(punctuated);
        assertEquals("jump", punctuated.word);
        assertEquals("標點要被清掉再顯示", "jumps", punctuated.displayWord());
    }

    @Test
    public void 真的不存在的字仍然查不到() {
        assertNull(dictionary.lookUp("zzzqqqxxx"));
        assertNull(dictionary.lookUp(""));
    }

    /** 一個變化形對應多個原形時取詞頻較高的。 */
    @Test
    public void 一形多原形取常用的() {
        DictEntry entry = dictionary.lookUp("analyses");
        assertNotNull(entry);
        assertTrue("analyse 或 analysis 都算合理",
                "analyse".equals(entry.word) || "analysis".equals(entry.word));
        assertTrue(entry.viaInflection());
    }
}
