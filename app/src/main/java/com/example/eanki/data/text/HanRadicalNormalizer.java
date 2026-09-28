package com.example.eanki.data.text;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「部首碼位」還原成一般漢字。
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <h2>這在修什麼</h2>
 *
 * 從 PDF 抽取文字時，某些字型會把一個字對應到<b>外觀完全相同、但碼位不同</b>的
 * 部首字元。例如「濫用」的「用」會變成 U+2F64（康熙部首 USE）而不是 U+7528。
 * 肉眼看不出差別，但它不是同一個字：
 *
 * <pre>
 *   搜「用」(U+7528)   → 0 筆
 *   搜「⽤」(U+2F64)   → 36 筆   ← 而使用者用任何輸入法都打不出這個碼位
 * </pre>
 *
 * 實際發生過：匯入的 1,464 筆裡有 594 筆（41%）中招，中文搜尋對那些字完全失效。
 *
 * <h2>為什麼「還原碼位」就夠，不需要再做繁簡轉換</h2>
 *
 * 這個陷阱是用<b>長得一模一樣</b>的部首字元去換原字，所以原字必然就是
 * 那個部首的統一漢字形。{@code ⼏} 代表的原字是「几」而不是「幾」，
 * {@code ⾥} 是「里」而不是「裡」—— 因為「幾」「裡」長得跟部首不像，
 * 字型不會那樣換。
 *
 * <p>對這些字再跑一次 s2twp 反而會弄錯：OpenCC 會把單獨的
 * 干→幹、斗→鬥、里→裡、幺→么，全都是錯的。所以康熙部首區
 * （U+2F00–U+2FD5）<b>只做碼位還原，不做任何繁簡判斷</b>。
 *
 * <h2>唯一的例外：CJK 部首補充區裡的簡體部首</h2>
 *
 * U+2E80–U+2EF3 這一區沒有 Unicode 相容分解，必須自己列對照表。
 * 其中名稱標著 {@code C-SIMPLIFIED} 的那些，統一漢字形本身就是簡體
 * （{@code ⻓}→长、{@code ⻛}→风），對繁體單字本而言那是錯的，
 * 所以對照表<b>直接指向繁體</b>（長、風）。這幾個對應沒有歧義。
 *
 * <p>本區其餘的字元是<b>部首偏旁</b>（{@code ⺅} 人字旁、{@code ⺖} 豎心旁），
 * 不是能獨立成字的字元，字型陷阱換不出它們，所以刻意不對應。
 * 真的遇到就由 {@link #unmappedRadicals} 回報，而不是猜一個對應默默存進去。
 */
public final class HanRadicalNormalizer {

    /** 康熙部首。整區都有 Unicode 相容分解，交給 NFKC。 */
    private static final int KANGXI_FIRST = 0x2F00;
    private static final int KANGXI_LAST = 0x2FD5;

    /** CJK 部首補充。沒有相容分解，要靠下面的對照表。 */
    private static final int SUPPLEMENT_FIRST = 0x2E80;
    private static final int SUPPLEMENT_LAST = 0x2EF3;

    /**
     * CJK 部首補充區裡「能獨立成字」的部首 → 繁體本字。
     *
     * <p>只列外觀等同於一個完整漢字的那些。偏旁形（{@code ⺅}、{@code ⺮}、
     * {@code ⺼}）刻意不列 —— 它們不會是字型陷阱的產物，硬給對應只會把
     * 猜測寫進使用者的資料。
     */
    private static final Map<Character, Character> SUPPLEMENT = buildSupplement();

    private static Map<Character, Character> buildSupplement() {
        Map<Character, Character> m = new HashMap<>();
        m.put('⺟', '母');   // CJK RADICAL MOTHER
        m.put('⺠', '民');   // CJK RADICAL CIVILIAN
        m.put('⻄', '西');   // CJK RADICAL WEST TWO
        m.put('⻅', '見');   // C-SIMPLIFIED SEE      见
        m.put('⻆', '角');   // SIMPLIFIED HORN
        m.put('⻇', '角');   // CJK RADICAL HORN
        m.put('⻉', '貝');   // C-SIMPLIFIED SHELL    贝
        m.put('⻋', '車');   // C-SIMPLIFIED CART     车
        m.put('⻑', '長');   // CJK RADICAL LONG ONE
        m.put('⻒', '長');   // CJK RADICAL LONG TWO
        m.put('⻓', '長');   // C-SIMPLIFIED LONG     长
        m.put('⻔', '門');   // C-SIMPLIFIED GATE     门
        m.put('⻘', '青');   // CJK RADICAL BLUE
        m.put('⻙', '韋');   // C-SIMPLIFIED TANNED LEATHER  韦
        m.put('⻚', '頁');   // C-SIMPLIFIED LEAF     页
        m.put('⻛', '風');   // C-SIMPLIFIED WIND     风
        m.put('⻜', '飛');   // C-SIMPLIFIED FLY      飞
        m.put('⻢', '馬');   // C-SIMPLIFIED HORSE    马
        m.put('⻣', '骨');   // CJK RADICAL BONE
        m.put('⻤', '鬼');   // CJK RADICAL GHOST
        m.put('⻥', '魚');   // C-SIMPLIFIED FISH     鱼
        m.put('⻦', '鳥');   // C-SIMPLIFIED BIRD     鸟
        m.put('⻧', '鹵');   // C-SIMPLIFIED SALT     卤
        m.put('⻨', '麥');   // SIMPLIFIED WHEAT      麦
        m.put('⻩', '黃');   // SIMPLIFIED YELLOW     黄
        m.put('⻪', '黽');   // C-SIMPLIFIED FROG     黾
        m.put('⻫', '齊');   // J-SIMPLIFIED EVEN     斉
        m.put('⻬', '齊');   // C-SIMPLIFIED EVEN     齐
        m.put('⻭', '齒');   // J-SIMPLIFIED TOOTH    歯
        m.put('⻮', '齒');   // C-SIMPLIFIED TOOTH    齿
        m.put('⻯', '龍');   // J-SIMPLIFIED DRAGON   竜
        m.put('⻰', '龍');   // C-SIMPLIFIED DRAGON   龙
        m.put('⻱', '龜');   // CJK RADICAL TURTLE
        m.put('⻲', '龜');   // J-SIMPLIFIED TURTLE   亀
        m.put('⻳', '龜');   // C-SIMPLIFIED TURTLE   龟
        return Collections.unmodifiableMap(m);
    }

    /**
     * 還原字串裡的部首碼位。沒有部首碼位時<b>回傳原物件</b>，不做任何配置。
     *
     * <p>只逐字處理落在兩個部首區段的字元。<b>不對整串做 NFKC</b> ——
     * 那會把全形括號「（）」換成半形，釋義的樣子就變了。
     */
    @Nullable
    public static String normalize(@Nullable String text) {
        if (text == null || !hasRadicalCodePoint(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(replacement(c));
        }
        return out.toString();
    }

    /** 這個字元該換成什麼；不需要換就回傳它自己。 */
    private static char replacement(char c) {
        Character mapped = SUPPLEMENT.get(c);
        if (mapped != null) {
            return mapped;
        }
        if (c >= KANGXI_FIRST && c <= KANGXI_LAST) {
            String unified = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
            // 康熙部首的相容分解一律是單一漢字；萬一不是就原樣保留
            if (unified.length() == 1) {
                return unified.charAt(0);
            }
        }
        return c;
    }

    public static boolean hasRadicalCodePoint(@Nullable String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (inRadicalBlock(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 還原之後仍然留在部首區段的字元。
     *
     * <p>匯入時用來回報「有沒有遇到對照表沒涵蓋的字」。
     * 猜一個對應默默存進去，比留著原字然後說出來糟得多。
     */
    @NonNull
    public static List<Character> unmappedRadicals(@Nullable String text) {
        List<Character> found = new ArrayList<>();
        String fixed = normalize(text);
        if (fixed == null) {
            return found;
        }
        for (int i = 0; i < fixed.length(); i++) {
            char c = fixed.charAt(i);
            if (inRadicalBlock(c) && !found.contains(c)) {
                found.add(c);
            }
        }
        return found;
    }

    private static boolean inRadicalBlock(char c) {
        return (c >= SUPPLEMENT_FIRST && c <= SUPPLEMENT_LAST)
                || (c >= KANGXI_FIRST && c <= KANGXI_LAST);
    }

    private HanRadicalNormalizer() {
    }
}
