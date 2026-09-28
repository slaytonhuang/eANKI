#!/usr/bin/env python3
"""產生 Phase 0 測試用的精簡 ECDICT SQLite。

schema 完全對齊 skywind3000/ECDICT 的 stardict 表，
之後換成真實資料時 Java 端一行都不用改。
"""
import os
import random
import sqlite3
import sys

OUT = sys.argv[1]
PAD_TO = int(sys.argv[2]) if len(sys.argv) > 2 else 0

# (word, phonetic, pos, definition, translation, tag, frq)
REAL = [
    ("apple", "'æpl", "n:100", "fruit with red or yellow or green skin and sweet to tart crisp whitish flesh", "n. 蘋果, 蘋果樹, 蘋果似的東西\nadj. 蘋果的", "zk gk", 2841),
    ("banana", "bə'nɑ:nə", "n:100", "elongated crescent-shaped yellow fruit with soft sweet flesh", "n. 香蕉, 芭蕉屬植物", "zk gk", 3319),
    ("orange", "'ɒrindʒ", "n:66/a:34", "round yellow to orange fruit of any of several citrus trees", "n. 橙, 桔子, 橙色\nadj. 橘色的, 橙色的", "zk gk cet4", 1868),
    ("dictionary", "'dikʃənəri", "n:100", "a reference book containing an alphabetical list of words with information about them", "n. 字典, 詞典", "zk gk cet4 cet6 ky", 3556),
    ("scanner", "'skænə", "n:100", "an electronic device that generates a digital representation of an image", "n. 掃描儀, 掃描器, 掃瞄機", "cet6 toefl", 8874),
    ("vocabulary", "və'kæbjuləri", "n:100", "a listing of the words used in some enterprise", "n. 詞彙, 詞彙表, 字彙", "cet4 cet6 ky toefl ielts", 5325),
    ("quick", "kwik", "a:78/r:22", "accomplished rapidly and without delay", "adj. 快的, 迅速的, 敏捷的\nadv. 快地, 迅速地", "zk gk cet4", 1093),
    ("brown", "braun", "a:52/n:36/v:12", "of a color similar to that of wood or earth", "adj. 褐色的, 棕色的\nn. 褐色, 棕色", "zk gk", 1436),
    ("fox", "fɒks", "n:92/v:8", "alert carnivorous mammal with pointed muzzle and ears and a bushy tail", "n. 狐狸, 狡猾的人\nvt. 欺騙", "gk cet4", 4231),
    ("jump", "dʒʌmp", "v:62/n:38", "move forward by leaps and bounds", "vi. 跳躍, 暴漲\nn. 跳躍, 暴漲", "zk gk cet4", 1712),
    ("lazy", "'leizi", "a:100", "moving slowly and gently", "adj. 懶惰的, 懶散的, 怠惰的", "zk gk cet4", 5017),
    ("dog", "dɒg", "n:94/v:6", "a member of the genus Canis that has been domesticated since prehistoric times", "n. 狗, 卑鄙的人\nvt. 尾隨, 跟蹤", "zk gk", 631),
    ("review", "ri'vju:", "n:52/v:48", "a new appraisal or evaluation", "n. 回顧, 複習, 評論\nvt. 回顧, 複習, 評論", "cet4 cet6 ky toefl ielts", 1120),
    ("memory", "'meməri", "n:100", "something that is remembered", "n. 記憶, 記憶力, 回憶, 記憶體", "zk gk cet4 cet6 ky toefl", 1074),
    ("phrase", "freiz", "n:82/v:18", "an expression consisting of one or more words", "n. 短語, 詞組, 措辭\nvt. 用言語表達", "cet4 cet6 ky toefl ielts", 2648),
    ("sentence", "'sentəns", "n:80/v:20", "a string of words satisfying the grammatical rules of a language", "n. 句子, 判決\nvt. 宣判, 判決", "zk gk cet4", 1804),
    ("translate", "træns'leit", "v:100", "restate words from one language into another language", "vt. 翻譯, 轉化, 解釋\nvi. 翻譯", "cet4 cet6 ky toefl ielts", 2924),
    ("camera", "'kæmərə", "n:100", "equipment for taking photographs", "n. 照相機, 攝影機", "zk gk cet4", 1930),
    ("image", "'imidʒ", "n:88/v:12", "an iconic mental representation", "n. 影像, 肖像, 形象\nvt. 想像, 反映", "gk cet4 cet6 ky toefl", 1006),
    ("offline", "'ɒf'lain", "a:60/r:40", "not connected to a computer network", "adj. 離線的, 脫機的\nadv. 離線地", "", 12045),
    ("recognize", "'rekəgnaiz", "v:100", "accept as valid", "vt. 認出, 承認, 賞識\nvi. 具結", "cet4 cet6 ky toefl ielts", 1553),
    ("familiar", "fə'miljə", "a:92/n:8", "well known or easily recognized", "adj. 熟悉的, 親近的, 通曉的", "cet4 cet6 ky toefl ielts", 1729),
    ("schedule", "'skedʒuəl", "n:56/v:44", "a temporally organized plan for matters to be attended to", "n. 時間表, 進度表, 計劃表\nvt. 安排, 排定", "cet4 cet6 ky toefl ielts", 1587),
    ("interval", "'intəvəl", "n:100", "a definite length of time marked off by two instants", "n. 間隔, 間距, 幕間休息", "cet4 cet6 ky toefl ielts", 2367),
    ("repeat", "ri'pi:t", "v:82/n:18", "to say, state, or perform again", "vt. 重複, 複述\nvi. 重說, 重做\nn. 重複", "zk gk cet4", 1526),
    ("pronounce", "prə'nauns", "v:100", "speak, pronounce, or utter in a certain way", "vt. 發音, 宣判, 斷言\nvi. 發音, 表態", "cet4 cet6 ky toefl", 4403),
    ("definition", "definiʃən", "n:100", "a concise explanation of the meaning of a word or phrase", "n. 定義, 解釋, 清晰度", "cet4 cet6 ky toefl ielts", 1742),
    ("example", "ig'zɑ:mpl", "n:100", "an item of information that is typical of a class or group", "n. 例子, 榜樣, 實例", "zk gk cet4", 528),
    ("frequency", "'fri:kwənsi", "n:100", "the number of occurrences within a given time period", "n. 頻率, 週率, 發生次數", "cet4 cet6 ky toefl ielts", 2255),
    ("database", "'deitəbeis", "n:100", "an organized body of related information", "n. 資料庫, 數據庫", "toefl", 5644),
    ("query", "'kwiəri", "n:52/v:48", "an instance of questioning", "n. 疑問, 質詢, 查詢\nvt. 詢問, 查詢", "cet6 toefl", 6318),
    ("index", "'indeks", "n:82/v:18", "a numerical scale used to compare variables with one another", "n. 索引, 指標, 指數\nvt. 為...編索引", "cet4 cet6 ky toefl ielts", 1919),
    ("card", "kɑ:d", "n:96/v:4", "one of a set of small pieces of stiff paper", "n. 卡片, 紙牌, 名片", "zk gk cet4", 1129),
    ("answer", "'ɑ:nsə", "n:56/v:44", "a statement that solves a problem or explains how to solve the problem", "n. 回答, 答案\nvt. 回答, 響應\nvi. 回答", "zk gk cet4", 597),
    ("question", "'kwestʃən", "n:84/v:16", "an instance of questioning", "n. 問題, 疑問\nvt. 詢問, 懷疑", "zk gk cet4", 300),
    ("difficult", "'difikəlt", "a:100", "not easy; requiring great physical or mental effort", "adj. 困難的, 難懂的, 執拗的", "zk gk cet4", 809),
    ("easy", "'i:zi", "a:88/r:12", "posing no difficulty; requiring little effort", "adj. 容易的, 舒適的, 寬裕的\nadv. 容易地", "zk gk cet4", 645),
    ("forget", "fə'get", "v:100", "dismiss from the mind; stop remembering", "vt. 忘記, 忽略\nvi. 忘記", "zk gk cet4", 1102),
    ("remember", "ri'membə", "v:100", "recall knowledge from memory", "vt. 記得, 想起\nvi. 記得, 記起", "zk gk cet4", 671),
    ("progress", "'prəugres", "n:62/v:38", "gradual improvement or growth or development", "n. 進步, 前進\nvi. 前進, 進步", "cet4 cet6 ky toefl ielts", 1381),
]

CJK = ("的一是不了人我在有他這中大來上國個到說們為子和你地出道也時年得就那要下"
       "以生會自著去之過家學對可她裡後小麼心多天而能好都然沒日於起還發成事只作當想看文無開手十用主行方又如前所本見經頭面公同三已老從動兩長知民卻等")
POS_POOL = ["n:100", "v:100", "a:100", "n:66/v:34", "a:52/n:48", "v:70/n:30"]
TAG_POOL = ["", "zk", "gk", "cet4", "cet4 cet6", "cet4 cet6 ky toefl", "ielts gre", "toefl ielts"]
LETTERS = "abcdefghijklmnopqrstuvwxyz"

if os.path.exists(OUT):
    os.remove(OUT)

conn = sqlite3.connect(OUT)
conn.execute("""
CREATE TABLE stardict (
    id         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    word       VARCHAR(64) COLLATE NOCASE NOT NULL UNIQUE,
    sw         VARCHAR(64) NOT NULL DEFAULT '',
    phonetic   VARCHAR(64),
    definition TEXT,
    translation TEXT,
    pos        VARCHAR(16),
    collins    INTEGER DEFAULT 0,
    oxford     INTEGER DEFAULT 0,
    tag        VARCHAR(64),
    bnc        INTEGER DEFAULT 0,
    frq        INTEGER DEFAULT 0,
    exchange   TEXT,
    detail     TEXT,
    audio      TEXT
)
""")

rows = []
for w, ph, pos, d, t, tag, frq in REAL:
    sw = "".join(c for c in w.lower() if c.isalnum())
    rows.append((w, sw, ph, d, t, pos, 3 if tag else 0, 1 if "gk" in tag else 0, tag, frq, frq, "", "", ""))

rng = random.Random(20260922)
real_words = {r[0] for r in rows}
while len(rows) < PAD_TO:
    n = rng.randint(4, 11)
    w = "".join(rng.choice(LETTERS) for _ in range(n))
    if w in real_words:
        continue
    real_words.add(w)
    # 用隨機中日韓字元填充，避免重複內容把壓縮率灌得不切實際
    trans = "\n".join(
        "%s. %s" % (rng.choice("nvadj"), "".join(rng.choice(CJK) for _ in range(rng.randint(2, 8))))
        for _ in range(rng.randint(1, 3))
    )
    defi = " ".join("".join(rng.choice(LETTERS) for _ in range(rng.randint(2, 9)))
                    for _ in range(rng.randint(6, 18)))
    frq = rng.randint(3000, 60000)
    rows.append((w, w, "'" + w[:6], defi, trans, rng.choice(POS_POOL), 0, 0,
                 rng.choice(TAG_POOL), frq, frq, "", "", ""))

conn.executemany(
    "INSERT INTO stardict (word, sw, phonetic, definition, translation, pos,"
    " collins, oxford, tag, bnc, frq, exchange, detail, audio)"
    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
conn.execute("CREATE INDEX idx_stardict_sw ON stardict (sw)")
conn.commit()
conn.execute("VACUUM")
conn.close()

print("%s  rows=%d  bytes=%d (%.2f MB)" % (
    OUT, len(rows), os.path.getsize(OUT), os.path.getsize(OUT) / 1048576.0))
