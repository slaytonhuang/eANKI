"""簡體轉繁體（台灣）。build_examples.py 與一次性資料修復共用同一套規則。

用 s2tw 而不是 s2twp 或 s2t：
  s2t   會轉成港式異體（里→裏、群→羣、吃→喫、床→牀），台灣不用
  s2twp 會做詞彙替換（文件→檔案、支持→支援、高清→高畫質），那是改用詞不是轉字
DENY 是逐筆檢查 s2tw 的每一處改動後，確認會弄錯的那幾個。
"""
import opencc

_cc = opencc.OpenCC('s2tw')

# s2tw 會弄錯的字元對：
#   了→瞭：語助詞「說明了」「走了」，不是「瞭解」。句子語料裡尤其多
#   岩→巖：礫岩、岩石在台灣是正確用法，巖是異體
#   准→準：「獲准」的准是准許，繁體本來就寫准
DENY = {('了', '瞭'), ('岩', '巖'), ('准', '準')}

# 音譯人名裡的「里」不是方位詞，不可以變「裡」
PROTECT = ['馬里奧', '謝里', '瑪麗', '希拉里', '莫里斯', '亨利']


# 中港台用語差異。s2twp 會做這種替換，但它同時會把「支持→支援」「文件→檔案」
# 也換掉，那是改寫意思。所以自己列一份<b>只含台灣不用、而且沒有歧義</b>的詞。
#
# 刻意不收的：
#   計算機→電腦   台灣的「計算機」是 calculator，兩邊都有但意思不同
#   質量→品質     台灣的「質量」在物理是 mass
#   項目→專案     台灣也說項目（運動項目）
#   自行車、信息   台灣本來就在用
#   朝鮮→北韓     朝鮮也是朝代名
#   沙特→沙烏地   台灣的「沙特」是哲學家 Sartre
LOCALE_PHRASES = [
    ('意大利', '義大利'),
    ('悉尼', '雪梨'),
    ('新西蘭', '紐西蘭'),
    ('軟件', '軟體'),
    ('硬件', '硬體'),
    ('網絡', '網路'),
    ('出租車', '計程車'),
    ('公交車', '公車'),
    ('西紅柿', '番茄'),
    ('土豆', '馬鈴薯'),
    ('激光', '雷射'),
    ('博客', '部落格'),
    ('視頻', '影片'),
]


def convert(text):
    if not text:
        return text
    holders = {}
    work = text
    for i, name in enumerate(PROTECT):
        if name in work:
            key = '%d' % i
            holders[key] = name
            work = work.replace(name, key)

    out = _cc.convert(work)
    for key, name in holders.items():
        out = out.replace(key, name)

    if len(out) != len(text):
        # 長度變了代表發生了逐字以外的替換，不套用
        return text
    out = ''.join(y if (x, y) not in DENY else x for x, y in zip(text, out))
    # s2tw 會把 账 轉成 賬，但台灣寫「帳戶」「帳號」，賬幾乎不用
    out = out.replace('賬', '帳')
    for src, dst in LOCALE_PHRASES:
        if src in out:
            out = out.replace(src, dst)
    return out
