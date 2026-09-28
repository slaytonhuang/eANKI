package com.example.eanki.data.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 確定是簡體的字的偵測。只偵測不轉換 —— 轉換要看上下文，轉錯會改掉釋義的意思。 */
public class SimplifiedDetectorTest {

    /** 這幾個就是這次實際漏網的字。 */
    @Test
    public void 抓得到確定是簡體的字() {
        assertTrue(SimplifiedDetector.hasSimplified("n.广告"));
        assertTrue(SimplifiedDetector.hasSimplified("准確无誤"));
        assertTrue(SimplifiedDetector.hasSimplified("小气"));
        assertTrue(SimplifiedDetector.hasSimplified("女儿"));
        assertTrue(SimplifiedDetector.hasSimplified("工厂"));
        assertTrue(SimplifiedDetector.hasSimplified("打网球"));
    }

    @Test
    public void 純繁體不會被誤報() {
        assertFalse(SimplifiedDetector.hasSimplified("n.會議，討論會，協商會"));
        assertFalse(SimplifiedDetector.hasSimplified("v.濫用；辱罵；虐待"));
        assertFalse(SimplifiedDetector.hasSimplified("（檢查風壓的）風洞"));
    }

    /**
     * 兩邊都合法的字不可以被誤報。
     *
     * <p>「公里」的里、「干擾」的干、「木板」的板、「北斗」的斗
     * 在繁體裡本來就這樣寫。把它們列進來會讓警告一直亮，久了就沒人看。
     */
    @Test
    public void 兩邊都合法的字不算簡體() {
        assertFalse(SimplifiedDetector.hasSimplified("公里"));
        assertFalse(SimplifiedDetector.hasSimplified("干擾"));
        assertFalse(SimplifiedDetector.hasSimplified("木板"));
        assertFalse(SimplifiedDetector.hasSimplified("北斗"));
        assertFalse(SimplifiedDetector.hasSimplified("幾何"));
    }

    @Test
    public void 英文與空值不受影響() {
        assertFalse(SimplifiedDetector.hasSimplified("accountant"));
        assertFalse(SimplifiedDetector.hasSimplified(null));
        assertFalse(SimplifiedDetector.hasSimplified(""));
    }

    @Test
    public void 回傳去重後的字並保持出現順序() {
        List<Character> found = SimplifiedDetector.find("广告；做广告；小气");
        assertEquals(2, found.size());
        assertEquals(Character.valueOf('广'), found.get(0));
        assertEquals(Character.valueOf('气'), found.get(1));
    }
}
