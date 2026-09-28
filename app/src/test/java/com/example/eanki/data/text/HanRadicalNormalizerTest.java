package com.example.eanki.data.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 部首碼位還原。修的是從 PDF 抽取文字時的字型編碼陷阱。
 *
 * <p>測試裡一律用 {@code \\uXXXX} 寫部首字元，不直接貼字形 ——
 * 這些字跟一般漢字長得一模一樣，貼進原始碼之後
 * 沒有人能從螢幕上看出這條測試到底在測什麼。
 */
public class HanRadicalNormalizerTest {

    /** 實際中招的例子：abuse 的釋義。 */
    @Test
    public void 康熙部首還原成一般漢字() {
        String broken = "v.濫⽤；辱罵";     // U+2F64 康熙部首 USE
        assertEquals("v.濫用；辱罵", HanRadicalNormalizer.normalize(broken));
    }

    @Test
    public void 常見的幾個康熙部首() {
        assertEquals("人", HanRadicalNormalizer.normalize("⼈"));
        assertEquals("行", HanRadicalNormalizer.normalize("⾏"));
        assertEquals("生", HanRadicalNormalizer.normalize("⽣"));
        assertEquals("心", HanRadicalNormalizer.normalize("⼼"));
    }

    /**
     * 康熙部首<b>不做繁簡判斷</b>。
     *
     * <p>字型陷阱是用長得一樣的字元換原字，所以 {@code ⼏} 的原字就是「几」。
     * 對它跑 s2twp 會得到「幾」，那是錯的 —— 這條測試把這個決定釘住。
     */
    @Test
    public void 康熙部首不可以被順手轉成繁體異體字() {
        assertEquals("几", HanRadicalNormalizer.normalize("⼏"));   // 不是「幾」
        assertEquals("里", HanRadicalNormalizer.normalize("⾥"));   // 不是「裡」
        assertEquals("干", HanRadicalNormalizer.normalize("⼲"));   // 不是「幹」
        assertEquals("斗", HanRadicalNormalizer.normalize("⽃"));   // 不是「鬥」
        assertEquals("尸", HanRadicalNormalizer.normalize("⼫"));   // 不是「屍」
    }

    /**
     * CJK 部首補充區的簡體部首要直接給繁體。
     *
     * <p>這一區沒有 Unicode 相容分解，NFKC 完全不動它，
     * 而且統一漢字形本身是簡體，對繁體單字本來說是錯的。
     */
    @Test
    public void 簡體部首直接對應到繁體本字() {
        assertEquals("長", HanRadicalNormalizer.normalize("⻓"));   // ⻓ 不是「长」
        assertEquals("風", HanRadicalNormalizer.normalize("⻛"));   // ⻛ 不是「风」
        assertEquals("見", HanRadicalNormalizer.normalize("⻅"));   // ⻅
        assertEquals("車", HanRadicalNormalizer.normalize("⻋"));   // ⻋
        assertEquals("頁", HanRadicalNormalizer.normalize("⻚"));   // ⻚
        assertEquals("飛", HanRadicalNormalizer.normalize("⻜"));   // ⻜
        assertEquals("魚", HanRadicalNormalizer.normalize("⻥"));   // ⻥
        assertEquals("齒", HanRadicalNormalizer.normalize("⻮"));   // ⻮
        assertEquals("民", HanRadicalNormalizer.normalize("⺠"));   // ⺠
    }

    /** 實際資料裡的 accelerate。 */
    @Test
    public void 混合兩區的實際例子() {
        String broken = "v.增加，增⻓；使加速；促進";
        assertEquals("v.增加，增長；使加速；促進",
                HanRadicalNormalizer.normalize(broken));
    }

    /**
     * <b>全形標點不可以被動到。</b>
     *
     * <p>對整串做 NFKC 會把「（）」換成半形「()」，釋義的樣子就變了。
     * 所以只能逐字處理落在部首區段的字元。
     */
    @Test
    public void 全形括號與其他標點原樣保留() {
        String broken = "（檢查⻛壓的）⻛洞";
        assertEquals("（檢查風壓的）風洞", HanRadicalNormalizer.normalize(broken));
    }

    @Test
    public void 一般文字原封不動而且不重新配置字串() {
        String clean = "n.會議，討論會，協商會";
        assertSame(clean, HanRadicalNormalizer.normalize(clean));
        assertSame(null, HanRadicalNormalizer.normalize(null));
    }

    @Test
    public void 英文與音標不受影響() {
        assertSame("accountant", HanRadicalNormalizer.normalize("accountant"));
        assertSame("/ə'kaʊntənt/", HanRadicalNormalizer.normalize("/ə'kaʊntənt/"));
    }

    @Test
    public void 偵測有沒有部首碼位() {
        assertTrue(HanRadicalNormalizer.hasRadicalCodePoint("濫⽤"));
        assertFalse(HanRadicalNormalizer.hasRadicalCodePoint("濫用"));
        assertFalse(HanRadicalNormalizer.hasRadicalCodePoint(null));
    }

    /**
     * 偏旁形沒有對應，要被回報而不是被猜一個對應。
     *
     * <p>{@code ⺅}（人字旁）不是能獨立成字的字元，字型陷阱換不出它。
     * 真的出現代表遇到了沒預期的情況，該說出來。
     */
    @Test
    public void 沒有對應的偏旁形會被回報而不是亂猜() {
        String odd = "測⺅試";                       // ⺅ 人字旁
        assertEquals(odd, HanRadicalNormalizer.normalize(odd));
        List<Character> unmapped = HanRadicalNormalizer.unmappedRadicals(odd);
        assertEquals(1, unmapped.size());
        assertEquals(Character.valueOf('⺅'), unmapped.get(0));
    }

    @Test
    public void 能對應的字元不會被列進未對應清單() {
        assertTrue(HanRadicalNormalizer.unmappedRadicals("濫⽤增⻓").isEmpty());
    }
}
