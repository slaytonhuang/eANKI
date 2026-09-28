#!/usr/bin/env python3
"""從 ECDICT 的 exchange 欄位建出「變化形 → 原形」索引表。

    python3 tools/build_forms.py <字典.db>

<h2>為什麼需要這張表</h2>

ECDICT 的 `exchange` 存在**原形那一列**上，列出它的各種變化形：

    jump → p:jumped/i:jumping/d:jumped/3:jumps/s:jumps

也就是「原形 → 變化形」。而變化形本身多半沒有自己的詞條
（精簡版裡 jumps / walked / bigger / went 都查不到，因為它們沒有獨立詞頻）。

所以掃到 `jumps` 時直接查一定落空。把 exchange 反過來展開成一張
`form → lemma` 的表，查不到時再查這張表就能還原。

<h2>代碼意義</h2>

    p  過去式        d  過去分詞      i  現在分詞
    3  第三人稱單數  s  複數          r  比較級
    t  最高級        f  不定式/其他   b/z  其他變體
    0  這個字的原形（方向相反，見下）
    1  0 的變化類型

**`0:` 的方向是相反的** —— 它出現在變化形自己的列上，指向原形。
兩個方向都收，能還原的字更多。
"""
import sqlite3
import sys

# 會展開成「變化形」的代碼。0 與 1 方向相反，另外處理。
FORWARD_CODES = {"p", "d", "i", "3", "s", "r", "t", "f", "b", "z"}

CREATE = """
CREATE TABLE IF NOT EXISTS word_form (
    form  VARCHAR(64) COLLATE NOCASE NOT NULL,
    lemma VARCHAR(64) COLLATE NOCASE NOT NULL,
    PRIMARY KEY (form, lemma)
)
"""


def parse(exchange):
    """把 'p:jumped/i:jumping' 解析成 [('p','jumped'), ('i','jumping')]。"""
    out = []
    for part in (exchange or "").split("/"):
        if ":" not in part:
            continue
        code, value = part.split(":", 1)
        code = code.strip()
        value = value.strip()
        if code and value:
            out.append((code, value))
    return out


def main():
    path = sys.argv[1]
    con = sqlite3.connect(path)
    con.execute("DROP TABLE IF EXISTS word_form")
    con.execute(CREATE)

    headwords = {row[0].lower() for row in con.execute("SELECT word FROM stardict")}

    pairs = set()
    rows = con.execute(
        "SELECT word, exchange FROM stardict"
        " WHERE exchange IS NOT NULL AND TRIM(exchange) <> ''").fetchall()

    for word, exchange in rows:
        for code, value in parse(exchange):
            if code in FORWARD_CODES:
                # word 是原形，value 是它的變化形
                form, lemma = value, word
            elif code == "0":
                # 方向相反：word 自己是變化形，value 才是原形
                form, lemma = word, value
            else:
                continue

            if form.lower() == lemma.lower():
                continue
            # 變化形自己就是詞條的話，直接查就會命中，不必進這張表
            if form.lower() in headwords:
                continue
            # 原形必須真的在精簡版裡，否則還原出來也查不到
            if lemma.lower() not in headwords:
                continue
            pairs.add((form, lemma))

    con.executemany("INSERT OR IGNORE INTO word_form (form, lemma) VALUES (?,?)",
                    sorted(pairs))
    con.execute("CREATE INDEX IF NOT EXISTS idx_word_form_form ON word_form (form)")
    con.commit()
    con.execute("VACUUM")

    total = con.execute("SELECT COUNT(*) FROM word_form").fetchone()[0]
    ambiguous = con.execute(
        "SELECT COUNT(*) FROM (SELECT form FROM word_form GROUP BY form HAVING COUNT(*) > 1)"
    ).fetchone()[0]
    print("  來源詞條      : %d" % len(rows))
    print("  變化形對應    : %d 筆" % total)
    print("  一形多原形    : %d 個（查詢時取詞頻最高的）" % ambiguous)
    con.close()


if __name__ == "__main__":
    main()
