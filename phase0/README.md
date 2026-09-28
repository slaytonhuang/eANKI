# Phase 0 — 可行性驗證

SPEC 第 8 節的三個 demo。刻意放在獨立的 `phase0/` 資料夾：這是一次性的驗證專案，
驗完就丟，不會變成正式 App 的基底。

**三個 demo 都已在實機驗證通過**（Galaxy Z Fold 5 / SM-F9460，外螢幕）。
完整 log 見 [run.log](run.log)。

| Demo | 結果 | 關鍵數字 |
|---|---|---|
| 1. ML Kit 辨識圖片文字 + bounding box | 通過 | 15 個單字，332 ms |
| 2. TextToSpeech 念 "apple" | 通過 | `networkRequired=false`（可離線） |
| 3. 載入 ECDICT 查單字 | 通過 | 首次複製 60 ms，單筆查詢 64 us（**用假資料量的**，見下） |

設定：`minSdk 24` / `compileSdk 34` / Java 17 / ViewBinding，無 Compose、無 Kotlin。

**AndroidManifest 裡一個 `<uses-permission>` 都沒有**，連 `INTERNET` 都沒宣告。
三個 demo 全部離線跑得起來，這本身就是 SPEC「全程離線、不需 API key」的證明。

---

## 怎麼跑

用 Android Studio 開 `eANKI/phase0` 這個資料夾（不是 `eANKI` 根目錄），按 Run。

命令列版本（`gradlew` 未附，二進位檔無法產生，直接用 Studio 下載好的 Gradle）：

```bash
cd /Users/sl/VibeCodingApp/eANKI/phase0 && JAVA_HOME="/Users/sl/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home" ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle :app:assembleDebug
```

> **Gradle JDK 必須是 21，不能用 Android Studio 內建的那顆。**
> Studio 現在 bundle 的 JBR 是 **JDK 25**，Gradle 8.7 不支援，會直接噴
> `Unsupported class file major version 69`。Studio 自己是抓 `~/Library/Java/JavaVirtualMachines/jbr-21.0.11`
> 才跑得起來，命令列要手動指定同一顆。

App 一啟動自動跑 Demo 1；Demo 2、Demo 3 要按畫面上的按鈕。
結果同時進 **Logcat（tag 過濾 `PHASE0`）** 和畫面下半部的 TextView。

---

## Demo 1 — ML Kit Text Recognition

辨識 `app/src/main/assets/sample_text.jpg`（1545×2000）。
走訪 `block → line → element`，**element 就是一個單字**。實測輸出節錄：

```
[block 0] lang=und rect=(l=41, t=46, r=493, b=111, w=452, h=65)
  [line] "The quick brown fox jumps"
    "The"  box=(l=42, t=48, r=95, b=80, w=53, h=32)  corners=(42,48) (95,48) (95,80) (42,80)
    "quick"  box=(l=116, t=48, r=215, b=80, w=99, h=32)  corners=(116,48) ...
  [line] "Over the Lazy dog."
[block 1] lang=und rect=(l=42, t=140, r=541, b=201, w=499, h=61)
  [line] "Vocabulary: apple, banana,"
    "Vocabulary:"  box=(l=42, t=140, r=235, b=174, w=193, h=34)  corners=...
    "apple,"  box=(l=247, t=140, r=363, b=174, w=116, h=34)  corners=...
辨識完成：2 個 block，15 個單字，耗時 332 ms
```

### 實測學到的三件事（都會影響 Phase 3）

1. **標點會黏在單字上。** `apple,` `dog.` `Vocabulary:` `scanner.` 全都帶著標點。
2. **大小寫會被改掉。** 原圖是 `over the lazy dog.`，ML Kit 回的是 `Over the Lazy dog.`。
   所以 **Phase 3 查字典前一定要先清洗：去標點 → 轉小寫 → 還原詞形。**
   Demo 3 的 SQL 用 `COLLATE NOCASE` 擋掉了大小寫，但標點擋不掉。
3. **`boundingBox` 是影像像素座標，不是 View 座標。** 疊加方框要自己換算縮放與旋轉。
   文字若是斜的，`boundingBox` 只是外接矩形，貼合文字的是 `cornerPoints`。

### 踩過的坑：透明背景會讓辨識結果變成 0

第一版素材是用 `sips` 從 PDF 轉出的 PNG，**背景是全透明（alpha=0、RGB 全 0）**。
Android 解碼後透明像素變黑，黑字在黑底上，ML Kit 一個字都認不到 —— 而且
**不會報錯，就只是安靜地回 0 個 block**。

改成 JPEG（無 alpha 通道）就正常了。`OcrDemo` 裡也補了保護：
`bitmap.hasAlpha()` 就先用 `Canvas` 壓上白底再送進去。
**Phase 3 開放相簿上傳時會遇到同樣的圖，這段保護要留著。**

## Demo 2 — TextToSpeech

```
[TTS] TTS 就緒，引擎 = com.google.android.tts
[TTS] voice=en-US-language locale=en_US networkRequired=false quality=400 latency=200
[TTS] speak("apple") 已排入佇列
[TTS] 開始發音：phase0-apple
[TTS] 發音結束：phase0-apple
```

**最關鍵的是 `networkRequired=false`** —— 這台裝置選到的是本地語音，
複習頁點擊發音不會卡網路，符合 SPEC 的離線要求。發音全程約 640 ms。

（若換裝置看到 `networkRequired=true` 或 `en-US 語音資料未安裝`，
要引導使用者到「設定 → 語言與輸入 → 文字轉語音 → 安裝語音資料」下載離線語音包。）

## Demo 3 — ECDICT 查詢（SPEC 說最關鍵的一個）

```
[DICT] assets/ecdict_mini.db 解壓後 10.26 MB
[DICT] 首次複製完成，耗時 60 ms（這是使用者第一次開 App 要等的時間）
[DICT] 開啟資料庫耗時 0 ms，共 50000 筆詞條
[DICT] 查詢 "apple" 耗時 64 us
[DICT]   word        = apple
[DICT]   phonetic    = /'æpl/
[DICT]   pos         = n:100
[DICT]   translation = n. 蘋果, 蘋果樹, 蘋果似的東西|adj. 蘋果的
[DICT]   definition  = fruit with red or yellow or green skin and sweet to tart crisp whitish flesh
[DICT]   tag         = zk gk
[DICT]   frq         = 2841
[DICT] 批次查 20 個字：命中 20，總耗時 1 ms（Phase 3 掃一張照片的成本）
```

（`|` 是 translation 欄位原本的換行，為了單行 log 才換掉的。）

### 結論：五萬詞內建，不需要首次啟動下載

SPEC 第 9 節列的第一個未決事項「字典檔精簡策略（內建精簡版 vs 首次啟動下載）」
**可以定案為內建**。三個數字都遠低於會造成體感的門檻：

| 項目 | 實測值 | 評估 |
|---|---|---|
| 字典檔解壓後 | 10.26 MB | 見下方 APK 拆解 |
| 首次複製（一次性） | **60 ms** | 使用者感覺不到 |
| 開啟資料庫 | **0 ms** | 每次啟動的成本可忽略 |
| 單筆查詢 | **64 us** | — |
| 批次查 20 字 | **1 ms** | 掃一張照片完全不用轉圈 |

「首次啟動下載」這個選項可以直接砍掉：為了省 10 MB 而換來一次網路下載、
下載失敗的處理、以及首次使用就違反「不等網路」原則，划不來。

### APK 體積拆解（實測）

| 內容 | 解壓後大小 |
|---|---|
| native lib × 4 個 ABI | 39.14 MB（x86_64 11.09 / x86 11.03 / arm64-v8a 10.55 / armeabi-v7a 6.47） |
| **assets/ecdict_mini.db** | **10.26 MB** |
| dex（程式碼＋依賴） | 8.85 MB |
| assets 其他（ML Kit 模型等） | 1.50 MB |
| res + META-INF | 0.70 MB |

- 完整 debug APK（四個 ABI）：**51 MB**
- 只含 `arm64-v8a` 的 debug APK：**22.3 MB**

**真正該看的是 22.3 MB 那個數字。** 實機只會裝到一個 ABI，
上架用 App Bundle 時 Google Play 會自動按 ABI 切分。這還是 debug 且沒開 R8，
release 開混淆後 dex 那 8.85 MB 會再縮掉一大截。

也就是說 **字典只佔最終下載量的十分之一左右，ML Kit 的模型與 native lib 才是大頭**。
容量這件事不必為了字典讓步。

### ⚠️ phase0 的字典是「假資料」，不要拿來當字典用

> **`phase0/app/src/main/assets/ecdict_mini.db` 裡只有 40 個真實單字，
> 其餘 49,960 筆是亂數產生的填充資料。它不是 ECDICT，查不到 `lock`、`run`、
> `beautiful` 這些最基礎的字。**
>
> 這份檔案存在的唯一目的，是量「五萬筆規模的 SQLite 有多大、載入多久、查詢多快」——
> 那三個數字是真的，詞庫內容是假的。它由當時的 `tools/make_dict.py` 產生
> （該腳本已刪除，見下方）。
>
> **正式的字典在 Phase 1 的 `app/src/main/assets/ecdict_mini.db`**，
> 由 `tools/build_dict.py` 從真實的完整版 ECDICT 篩出 49,500 筆（釋義已轉繁體）。
> phase0 這份刻意保留原狀，因為本文件記錄的效能數字都是用它量的。

僅有的 40 個真實詞條（涵蓋 demo 1 那張圖裡的所有字）：

> apple banana orange dictionary scanner vocabulary quick brown fox jump lazy dog
> review memory phrase sentence translate camera image offline recognize familiar
> schedule interval repeat pronounce definition example frequency database query
> index card answer question difficult easy forget remember progress

schema 是對齊 skywind3000/ECDICT 的 `stardict` 表做的，所以換成真實資料時
`DictDemo.java` 一行都沒改 —— 這點有被後續驗證過。

**這份假資料造成過一次真實的誤判：** Phase 1 把它接上手動輸入畫面之後，
它看起來就像一個能用的字典，直到使用者發現連 `lock` 都查不到。
教訓是測試用的假資料不該以正式檔名躺在 assets 裡，
或至少要在接上 UI 的那一刻就換掉。

### 容量推估（用假資料量的，僅供規模參考）

| 詞條數 | .db 檔案 | gzip 後 |
|---|---|---|
| 1 萬 | 2.05 MB | 1.20 MB |
| **5 萬** | **10.26 MB** | **6.08 MB** |
| 10 萬 | 20.57 MB | 12.21 MB |

**實際數字：**真實 ECDICT 篩出的 **49,500 筆是 11.60 MB**，
比這裡用同規模假資料推估的 10.26 MB 高約 13%
（真實資料的 `exchange` 詞形變化欄位有內容，填充資料是空的）。
推估的量級是對的，細節偏樂觀。

### 其他確認的點

- **SQLite 不能直接開 assets 裡的檔案**（在 APK 內是壓縮的，拿不到 fd），
  必須先整包複製到 app 私有目錄。程式會比對檔案大小，第二次之後啟動直接跳過。
- **`COLLATE NOCASE` 讓大小寫不敏感**，所以 demo 1 誤判成的 `Lazy` 一樣查得到。
  但標點沒有處理，這是 Phase 3 清洗管線要做的事。

---

## 建置上踩到的兩個坑（都已修）

1. **Kotlin stdlib 重複類別。** ML Kit 拉進 `kotlin-stdlib-jdk7/jdk8:1.6.21`，
   appcompat 拉進 `kotlin-stdlib:1.8.22`。Kotlin 1.8 起 jdk7/jdk8 的內容已併回主 artifact，
   兩邊同時存在就在 `checkDebugDuplicateClasses` 炸掉。
   解法是在 `app/build.gradle` 用 `constraints` 把 jdk7/jdk8 拉到 1.8.22。
2. **Gradle JDK 版本。** 見上方「怎麼跑」的警告。
