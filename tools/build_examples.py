#!/usr/bin/env python3
"""把 Tatoeba 的中英對照句子做成離線例句索引，寫進 ecdict_mini.db。

定位（SPEC 第 8 節 Phase 4）：**備援**。掃描到的字、字典查得到釋義但沒有例句時才用。
使用者自己匯入的例句（is_custom=1）永遠優先，字典整包更新時不會被動到。

用法：
    python3 tools/build_examples.py <tatoeba 目錄> <ecdict_mini.db>

產生兩張表（正規化，不把句子複製到每個字底下）：
    tatoeba_sentence(id, text_en, text_zh)
    tatoeba_index(word, sentence_id, rank)
"""
import os
import re
import sqlite3
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from zh_convert import convert as to_traditional

# 句子長度。太短沒有語境，太長在複習卡片上讀不完。
MIN_WORDS, MAX_WORDS = 4, 12
MIN_ZH, MAX_ZH = 3, 40
# 每個單字最多留幾句。這是檔案大小的主要旋鈕。
MAX_PER_WORD = 2
# 難度上限。放寬只多撿到「其他字很難」的句子，對學目標字沒幫助。
MAX_DIFFICULTY = 6

# Tatoeba 是開放投稿的語料庫，裡面有不少成人內容。
# 這是拿來在複習卡片上看的，整句含這些詞就跳過 ——
# 單字本身（sex、drunk…）還是查得到，只是不會配到這種例句。
BLOCKED = re.compile(
    r"\b(sex|sexy|sexual|porn|penis|vagina|breasts?|nude|naked|orgasm|"
    r"masturbat\w*|prostitut\w*|rape|raped|fuck\w*|shit|bitch|bastard|"
    r"whore|slut|erection|condom|brothel)\b")

TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?")
ASCII_OK = re.compile(r"^[\x20-\x7E]+$")


def load_sentences(path, lang_filter=None):
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.rstrip('\n').split('\t')
            if len(parts) < 3:
                continue
            out[int(parts[0])] = parts[2]
    return out


def main(tatoeba_dir, db_path):
    print('讀 Tatoeba…')
    eng = load_sentences(os.path.join(tatoeba_dir, 'eng_sentences.tsv'))
    cmn = load_sentences(os.path.join(tatoeba_dir, 'cmn_sentences.tsv'))
    pairs = []
    with open(os.path.join(tatoeba_dir, 'eng-cmn_links.tsv'), encoding='utf-8') as f:
        for line in f:
            a, b = line.split()
            a, b = int(a), int(b)
            if a in eng and b in cmn:
                pairs.append((eng[a], cmn[b]))
    print('  英文 %d 句 / 中文 %d 句 / 配對 %d 組' % (len(eng), len(cmn), len(pairs)))

    db = sqlite3.connect(db_path)
    # 詞頻：拿來判斷「這句話裡其他字難不難」。unknown 的字給重罰。
    rank = {}
    for w, frq, bnc in db.execute('SELECT word, frq, bnc FROM stardict'):
        r = min([x for x in (frq, bnc) if x] or [0])
        rank[w] = r if r else 999999
    # 詞形還原。
    #
    # word_form 原本只收「本身不是詞條的變化形」—— 查釋義時直接命中必須優先
    # （saw 是鋸子，不是 see）。但例句的需求相反：abandoning 沒有自己的例句時，
    # 用 abandon 的完全合理，而 abandoning 剛好也是個詞條。
    #
    # 所以把缺的那些補進同一張表，多一個 is_headword 旗標區分：
    #   查釋義 → WHERE form=? AND is_headword=0
    #   查例句 → WHERE form=?
    # 另開一張表會跟 word_form 重疊九成，白白多 1.6 MB。
    FORWARD = set('pdi3srtfbz')
    lemma = {}
    for word, exchange in db.execute(
            "SELECT word, exchange FROM stardict WHERE exchange IS NOT NULL AND exchange <> ''"):
        for part in exchange.split('/'):
            if ':' not in part:
                continue
            code, form = part.split(':', 1)
            form = form.strip().lower()
            if code not in FORWARD or not form or form == word:
                continue
            # 同一個形對到多個原形時，保留詞頻較高（rank 較小）的那個
            old = lemma.get(form)
            if old is None or rank.get(word, 999999) < rank.get(old, 999999):
                lemma[form] = word
    # word_form.form 是 COLLATE NOCASE，比對時要一起轉小寫，
    # 否則 Abandoning / abandoning 會被當成兩筆，插入時撞主鍵
    existing = {f.lower() for (f,) in db.execute('SELECT form FROM word_form')}
    extra = sorted((f, l) for f, l in lemma.items() if f not in existing)
    print('  字典 %d 詞條 / 完整詞形 %d 筆（word_form 已有 %d，補 %d 筆本身也是詞條的）'
          % (len(rank), len(lemma), len(existing), len(extra)))

    def lemmatize(tok):
        """回傳這個 token 該算在哪些字底下（自己 + 原形）。"""
        out = []
        if tok in rank:
            out.append(tok)
        base = lemma.get(tok)
        if base and base != tok and base in rank:
            out.append(base)
        return out

    print('篩選與評分…')
    # candidates[word] = list of (score, en, zh)
    candidates = defaultdict(list)
    seen_pairs = set()
    kept_pairs = 0
    for en, zh in pairs:
        if not ASCII_OK.match(en):
            continue
        tokens = TOKEN.findall(en.lower())
        if not (MIN_WORDS <= len(tokens) <= MAX_WORDS):
            continue
        if not (MIN_ZH <= len(zh) <= MAX_ZH):
            continue
        if (en, zh) in seen_pairs:
            continue
        seen_pairs.add((en, zh))

        lemmas = []
        difficulty = 0
        for t in tokens:
            ls = lemmatize(t)
            if not ls:
                difficulty += 3          # 字典沒有的字，多半是專有名詞或罕見詞
                continue
            lemmas.extend(ls)
            r = min(rank.get(l, 999999) for l in ls)
            if r > 20000:
                difficulty += 2
            elif r > 8000:
                difficulty += 1
        if difficulty > MAX_DIFFICULTY:
            continue
        if BLOCKED.search(en.lower()):
            continue

        zh_trad = to_traditional(zh)
        # 分數越低越好：先看句子難度，再偏好短句
        score = difficulty * 10 + len(tokens)
        kept_pairs += 1
        for l in set(lemmas):
            candidates[l].append((score, en, zh_trad))

    print('  通過篩選的句子 %d 組，涵蓋 %d 個單字' % (kept_pairs, len(candidates)))

    print('每個字挑前 %d 句…' % MAX_PER_WORD)
    sentence_id = {}
    sentences = []
    index_rows = []
    for word in sorted(candidates):
        # 中文一樣就算重複。Tatoeba 常有「closed at five」「ended at five」
        # 這種英文不同、中文完全相同的句子，兩句一起放在卡片上只是佔位置。
        best = []
        seen_zh = set()
        seen_en = set()
        for score, en, zh in sorted(candidates[word]):
            # 中文或英文任一邊重複都算重複：Tatoeba 常有同一句英文配兩種中文譯法，
            # 也常有兩句英文配同一句中文。兩種都不該同時出現在卡片上。
            if zh in seen_zh or en in seen_en:
                continue
            seen_zh.add(zh)
            seen_en.add(en)
            best.append((score, en, zh))
            if len(best) == MAX_PER_WORD:
                break
        for pos, (_, en, zh) in enumerate(best):
            key = (en, zh)
            sid = sentence_id.get(key)
            if sid is None:
                sid = len(sentences) + 1
                sentence_id[key] = sid
                sentences.append((sid, en, zh))
            index_rows.append((word, sid, pos))

    print('  實際存 %d 句、%d 筆索引' % (len(sentences), len(index_rows)))

    print('寫進字典庫…')
    db.executescript("""
        DROP TABLE IF EXISTS tatoeba_index;
        DROP TABLE IF EXISTS tatoeba_sentence;
        CREATE TABLE tatoeba_sentence (
            id INTEGER PRIMARY KEY,
            text_en TEXT NOT NULL,
            text_zh TEXT NOT NULL
        );
        CREATE TABLE tatoeba_index (
            word TEXT NOT NULL,
            sentence_id INTEGER NOT NULL,
            rank INTEGER NOT NULL
        );
    """)
    db.executemany('INSERT INTO tatoeba_sentence VALUES (?,?,?)', sentences)
    db.executemany('INSERT INTO tatoeba_index VALUES (?,?,?)', index_rows)
    # word_form 補上旗標欄與缺的那幾千筆
    cols = [r[1] for r in db.execute('PRAGMA table_info(word_form)')]
    if 'is_headword' not in cols:
        db.execute('ALTER TABLE word_form ADD COLUMN is_headword INTEGER NOT NULL DEFAULT 0')
    db.execute('DELETE FROM word_form WHERE is_headword = 1')
    db.executemany('INSERT INTO word_form (form, lemma, is_headword) VALUES (?,?,1)', extra)
    db.execute('CREATE INDEX idx_tatoeba_word ON tatoeba_index(word, rank)')
    db.commit()
    db.execute('VACUUM')
    db.close()
    print('完成。字典檔現在 %.1f MB' % (os.path.getsize(db_path) / 1024 / 1024))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
