#!/usr/bin/env python3
"""從完整版 ECDICT 篩出精簡版字典，並把中文釋義轉成繁體。

    pip install opencc
    python3 tools/build_dict.py <完整版 stardict.db> <輸出 .db> [--limit N]

資料來源：skywind3000/ECDICT 的預建 SQLite
    https://github.com/skywind3000/ECDICT/releases/download/1.0.28/ecdict-sqlite-28.zip
完整版 3,402,564 筆 / 851 MB，絕大多數是專業術語、變形、專有名詞與片語。

篩選規則
--------
1. **有考試標籤的詞無條件保留**：zk / gk / cet4 / cet6 / ky / toefl / ielts / gre。
   不受詞頻或任何其他條件影響。
2. **其餘的詞要有詞頻排名**：`bnc > 0` 或 `frq > 0`。
3. **大寫開頭且沒有考試標籤的詞，排名門檻收緊到 5000**（見下）。
4. 必須有中文釋義（`translation` 非空）。

**踩過的坑：`bnc` 與 `frq` 是「排名」，數字越小越常用，而沒有排名的詞是
`NULL`（255 萬筆）或 `0`（80 萬筆），不是一個很大的數字。**
所以 `ORDER BY frq LIMIT 50000` 會把 NULL 和 0 排在最前面，
撈出來的全是生僻字，真正的高頻字一個都進不來。必須先把沒排名的排除再排序。

兩個排名合併時取 `MIN()` 而不是平均：BNC（英式書面語）與 COCA（美式當代語料）
的涵蓋範圍不同，只要在任一語料庫裡算常用就該收進來。
取平均會讓只在單一語料庫有排名的詞被系統性低估。

為什麼大寫詞要另外設門檻
------------------------
只靠規則 2 的話，會混進 8,496 個沒有考試標籤的大寫專有名詞 ——
英國郡鎮（Cambridgeshire、Staffordshire、Coventry）、人名（Trevor、Eleanor、
Mozart）、機構縮寫（CNRS、ANOVA、CBE），對背單字毫無價值卻佔掉體積。

但不能無腦砍掉所有大寫字：China、English、Monday、America 都是大寫。
實際查過之後發現，**這些該留的大寫詞全都有考試標籤**，所以規則 1 已經保住它們了
（共 153 筆）。門檻只作用在「大寫 + 沒標籤」這個交集上。

門檻取 5000 而非 10000：10000 會留下 566 筆，裡面是 Miami、Manhattan、
Philadelphia、Greenwich、Fulham 這類地名與 Ralph、Trevor 這類人名，
學習價值等於零。5000 留下 155 筆，以國名、首都、國際組織縮寫為主
（USA、IBM、Israel、Austria、Denmark、Vienna、Mr、Mrs、UK、DNA、PC、CEO），
這些至少是「看到要認得」的程度。

驗證過重要縮寫都不會被誤殺：DNA / AIDS / TV / CD / PC / UK / USA / CEO /
DVD / OK / Mr / Mrs / April / Friday / Easter / Bible / Mars / Earth 全部保留
（多數靠考試標籤，少數靠排名）。

簡體轉繁體
----------
ECDICT 的 `translation` 是簡體中文（約三分之一的詞條含簡體字）。
用 OpenCC 的 `s2twp` 設定在**建置時一次轉完**存進 db：
簡體 → 繁體（台灣正體），並套用台灣慣用詞彙（軟件→軟體、預訂等）。

**App 執行期不做任何轉換**，`DictionaryRepository` 一行都沒改，
查出來就是繁體，沒有額外成本。

丟棄的欄位：`detail`、`audio`（App 用不到）。
保留 `exchange`（詞形變化表），之後要做 lemmatization 會用到。
"""
import argparse
import os
import sqlite3
import sys

# 沒有排名時代入的哨兵值，必須大於任何真實排名
UNRANKED = 9999999

# 大寫開頭且無考試標籤的詞，排名要好過這個數字才收
PROPER_NOUN_MAX_RANK = 5000

SELECT_FILTERED = """
SELECT
    s.word, s.sw, s.phonetic, s.definition, s.translation, s.pos,
    s.collins, s.oxford, s.tag, s.bnc, s.frq, s.exchange,
    MIN(
        CASE WHEN s.bnc IS NULL OR s.bnc = 0 THEN {unranked} ELSE s.bnc END,
        CASE WHEN s.frq IS NULL OR s.frq = 0 THEN {unranked} ELSE s.frq END
    ) AS rank_score,
    CASE WHEN TRIM(COALESCE(s.tag, '')) <> '' THEN 1 ELSE 0 END AS tagged
FROM stardict AS s
WHERE
    TRIM(COALESCE(s.translation, '')) <> ''
    AND (
        -- 規則 1：有考試標籤，無條件保留
        TRIM(COALESCE(s.tag, '')) <> ''
        OR (
            -- 規則 2：有詞頻排名
            (s.bnc > 0 OR s.frq > 0)
            -- 規則 3：大寫開頭的專有名詞要再嚴格一點
            AND (
                s.word NOT GLOB '[A-Z]*'
                OR MIN(
                    CASE WHEN s.bnc IS NULL OR s.bnc = 0 THEN {unranked} ELSE s.bnc END,
                    CASE WHEN s.frq IS NULL OR s.frq = 0 THEN {unranked} ELSE s.frq END
                ) < {proper_max}
            )
        )
    )
-- 有考試標籤的排在前面，確保 --limit 截斷時它們不會被砍掉
ORDER BY tagged DESC, rank_score ASC, s.word ASC
""".format(unranked=UNRANKED, proper_max=PROPER_NOUN_MAX_RANK)

CREATE_TABLE = """
CREATE TABLE stardict (
    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    word        VARCHAR(64) COLLATE NOCASE NOT NULL UNIQUE,
    sw          VARCHAR(64) NOT NULL DEFAULT '',
    phonetic    VARCHAR(64),
    definition  TEXT,
    translation TEXT,
    pos         VARCHAR(16),
    collins     INTEGER DEFAULT 0,
    oxford      INTEGER DEFAULT 0,
    tag         VARCHAR(64),
    bnc         INTEGER DEFAULT 0,
    frq         INTEGER DEFAULT 0,
    exchange    TEXT
)
"""

INSERT = ("INSERT INTO stardict (word, sw, phonetic, definition, translation,"
          " pos, collins, oxford, tag, bnc, frq, exchange)"
          " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="完整版 stardict.db")
    parser.add_argument("output", help="輸出的精簡版 .db")
    parser.add_argument("--limit", type=int, default=0,
                        help="最多保留幾筆（0 = 不設限）")
    parser.add_argument("--no-convert", action="store_true",
                        help="跳過簡轉繁（除錯用）")
    args = parser.parse_args()

    if not os.path.exists(args.source):
        sys.exit("找不到來源：" + args.source)
    if os.path.exists(args.output):
        os.remove(args.output)

    convert = None
    if not args.no_convert:
        try:
            import opencc
        except ImportError:
            sys.exit("需要 OpenCC：pip install opencc")
        # s2twp = 簡體 → 繁體（台灣正體），含台灣慣用詞彙轉換
        convert = opencc.OpenCC("s2twp").convert

    src = sqlite3.connect("file:%s?mode=ro" % args.source, uri=True)
    out = sqlite3.connect(args.output)
    out.execute(CREATE_TABLE)

    sql = SELECT_FILTERED
    if args.limit > 0:
        sql += "\nLIMIT %d" % args.limit

    inserted = 0
    tagged_kept = 0
    converted = 0
    batch = []

    for row in src.execute(sql):
        row = list(row[:12])
        if convert is not None and row[4]:
            original = row[4]
            row[4] = convert(original)
            if row[4] != original:
                converted += 1
        batch.append(row)
        tagged_kept += 1 if row[8] and row[8].strip() else 0

        if len(batch) >= 5000:
            out.executemany(INSERT, batch)
            inserted += len(batch)
            batch = []

    if batch:
        out.executemany(INSERT, batch)
        inserted += len(batch)

    # App 只用 word 查詢；word 的 UNIQUE 約束已建索引，sw 留給之後的模糊查詢
    out.execute("CREATE INDEX idx_stardict_sw ON stardict (sw)")
    out.commit()
    out.execute("VACUUM")
    out.close()
    src.close()

    size = os.path.getsize(args.output)
    print(args.output)
    print("  筆數        : %d（其中帶考試標籤 %d）" % (inserted, tagged_kept))
    print("  釋義轉繁體  : %d 筆有變動%s"
          % (converted, "" if convert else "（已用 --no-convert 跳過）"))
    print("  檔案大小    : %d bytes (%.2f MB)" % (size, size / 1048576.0))


if __name__ == "__main__":
    main()
