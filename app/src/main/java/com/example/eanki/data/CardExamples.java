package com.example.eanki.data;

import androidx.annotation.NonNull;

import com.example.eanki.data.dict.DictExample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一張複習卡要顯示的例句。
 *
 * <p>{@link #fromDictionary} 區分這幾句是使用者自己的，還是字典的備援。
 * UI 要把來源講出來 —— 備援句是機器挑的，品質跟使用者自己整理的不同等級，
 * 讓人知道差別才不會把它當成教材的一部分。
 */
public final class CardExamples {

    public static final CardExamples NONE =
            new CardExamples(Collections.emptyList(), false);

    public final List<DictExample> examples;
    public final boolean fromDictionary;

    CardExamples(@NonNull List<DictExample> examples, boolean fromDictionary) {
        this.examples = examples;
        this.fromDictionary = fromDictionary;
    }

    public boolean isEmpty() {
        return examples.isEmpty();
    }

    static CardExamples own(@NonNull List<DictExample> examples) {
        return examples.isEmpty() ? NONE : new CardExamples(examples, false);
    }

    static CardExamples fallback(@NonNull List<DictExample> examples) {
        return examples.isEmpty() ? NONE : new CardExamples(new ArrayList<>(examples), true);
    }
}
