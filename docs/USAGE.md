# eANKI 日常使用須知

自用工具，開發已結束。這份文件記的是「平常怎麼用」與「哪些事要小心」。
設計與架構決策在 `docs/SPEC.md`。

---

## 1. 備份

**多久一次：每個月一次，或任何一次大量加字之後。**

平常一天複習十幾張卡，就算掉了一天的進度也還好。真正不能掉的是
**幾個月累積下來的排程與熟悉度**——那是重新匯入 CSV 救不回來的東西
（CSV 沒有 `due_date`、沒有 ease factor、沒有最近三次評分）。

所以真正該備份的時機是：

- 剛匯入一批新單字表
- 要換手機、要重裝 App、要清除 App 資料之前
- 距離上次備份超過一個月

**怎麼做**：設定 → 資料備份 → 匯出備份 → 選一個位置（建議雲端硬碟，
不要只留在手機的「下載」資料夾——手機掉了備份也跟著掉）。
設定頁會顯示「上次備份」的時間，沒備份過時是琥珀色，那是提醒不是裝飾。

**備份檔含什麼**：單字、音標、釋義、例句、標籤、熟悉度、複習排程、
複習流水紀錄、來源照片。是完整的一份，還原後會回到匯出當下的狀態。

**還原要注意**：是<b>整批取代</b>，不是合併。備份之後新增的字會消失。
按下去之前會跳確認框寫清楚會取代掉幾個字。

**備份檔是純文字 JSON**，用任何文字編輯器都打得開，
所以就算哪天 App 壞了，資料本身還是讀得出來的。

---

## 2. 離線字典

**不用更新，也沒有更新入口。**

字典是內建在 App 裡的（16.2 MB，49,500 詞條 + 12,028 句例句），
跟著 App 版本走。設定頁那顆「重建字典檔」是給**檔案損壞**時修復用的
（複製到一半被砍、儲存空間滿了），平常不需要按。

字典要換版本的話是重新建置 App，流程在 `tools/build_dict.py`、
`tools/build_forms.py`、`tools/build_examples.py`。

---

## 3. 每日提醒

提醒靠 WorkManager，而 Android 的 Doze 會把背景工作往後推。
如果發現提醒常常晚一兩個小時才到：

1. 設定 → 提醒 → 電池最佳化，看那一列顯示「會被延後」還是「不受限制」
2. 點進去把 eANKI 設成不最佳化

同一區塊還有「通知權限」那一列。沒有權限的話，提醒會被系統**靜靜丟掉**——
Worker 有跑、通知沒出現，看起來就像功能壞了。兩列都會顯示目前狀態。

---

## 4. 要特別小心的操作

### 從備份還原
整批取代，無法復原。確認框上的數字要看一眼再按。

### 刪除單字
會連帶刪掉那個字的複習排程、例句、標籤關聯、複習紀錄（外鍵 CASCADE）。
刪除後有 Snackbar 可以復原，但**離開畫面就來不及了**。

### 調整每日上限
新字上限可以設成 0（「先把手上的消化完」），複習上限最低是 5。
改設定<b>不會影響正在進行的複習</b>——佇列在按下「開始複習」時就定案了。

### 匯入 CSV
- 已存在的字（headword 相同）會跳過，不會蓋掉
- 匯入摘要會警告兩件事：無法還原的部首字元、殘留的簡體字。
  <b>看到警告要回頭修來源檔</b>，那些字看起來正常但搜尋會搜不到
  （這個坑實際發生過，1,464 筆裡有 594 筆中招，詳見 SPEC 第 9 節）

---

## 5. 開發時的地雷

之後如果要再加功能，這幾件事先知道會少踩很多坑。

### 地雷一：instrumented test 會清光正式資料庫

**實際發生過。**`ReminderWorkerTest` 對真正的 `UserDatabase` 呼叫
`clearAllTables()`，而 `connectedAndroidTest` 跑完預設會解除安裝 App——
1,464 筆單字連同幾個月的排程一起消失。

現在的防線有三層，**加新測試時三層都要顧**：

1. 測試一律用 in-memory 資料庫，透過 `UserDatabase.overrideForTest()` 換掉單例，
   `@After` 傳 null 還原
2. 設定用 `AppSettings.forTest(context, "別的檔名")`，不碰正式的 SharedPreferences
3. `gradle.properties` 裡的 `android.injected.androidTest.leaveApksInstalledAfterRun=true`
   讓測試跑完不解除安裝

`ScanImageStore` 也有 `ScanImageStore(File)` 這個測試用建構子，同樣的道理。

**寫新測試之前先備份**：
```
adb shell "run-as com.example.eanki cat databases/eanki_user.db" > backup.db
```

### 地雷二：Gradle JDK 必須是 21

Android Studio 內建的 JBR 是 JDK 25，Gradle 8.7 會直接拒絕
（`Unsupported class file major version 69`）。

```
JAVA_HOME=/Users/sl/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
```

### 地雷三：兩個資料庫不要搞混

- `ecdict_mini.db`（assets，唯讀）——字典。用一般 `SQLiteDatabase` 開，**不是 Room**
- `eanki_user.db`（Room）——使用者資料。可備份、要 migration

字典的值是**複製**進使用者庫的，不存外鍵。字典整包換版本時，
使用者資料不會跟著壞。這條界線不要破壞。

同一個道理：**系統能重新產生的資料不要固化進使用者庫**。
Tatoeba 備援例句是顯示時才查、不寫進 `example` 表；
加強複習不寫 `review_log`。兩個都是為了讓備份只含真正屬於使用者的東西。

### 地雷四：Room migration 要記得加

改 entity 就要寫 migration 並 +1 版本號，否則使用者一升級就
`IllegalStateException` 然後資料全毀。目前是 **v4**：

- v1→v2 `review_state.released_at`
- v2→v3 `review_log` 表
- v3→v4 部首碼位的一次性資料修復

`app/schemas/` 下的匯出 schema 要一起 commit，`MigrationTestHelper` 靠它建舊版資料庫。

### 地雷五：摺疊裝置會重建 Activity

Galaxy Z Fold 展開／闔上會整個重建 Activity。任何放在 Activity 成員變數的狀態
都會掉——複習進度、翻面狀態、勾選清單、照片展開狀態，全部要放 ViewModel。
這是最容易漏、使用者最有感的 bug。

另外 `screencap` 在雙螢幕裝置上會抓錯螢幕（抓到闔上的那面，截圖全黑）。
要指定 display id：
```
adb shell dumpsys SurfaceFlinger --display-id
adb shell screencap -p -d <id> /sdcard/s.png
```

### 地雷六：繁簡轉換不要用 s2twp

`s2twp` 會做詞彙替換（文件→檔案、支持→支援、高清→高畫質），那是改寫意思。
`s2t` 會轉成港式異體（里→裏、群→羣、吃→喫）。
**用 `s2tw`**，而且要排除幾個過度轉換的字——規則都在 `tools/zh_convert.py`，
新的轉換需求直接用那一支，不要各寫一份。

### 地雷七：Material Slider 的值要對齊 stepSize

`Slider.setValue()` 對「不是 valueFrom + n×stepSize」的數字會直接丟例外。
夾限擋得住範圍、擋不住步進——備份還原來的舊設定值會讓設定頁一開就當掉。
`SettingsFragment.setSliderValue()` 有處理，新增滑桿時照著用。

---

## 6. 常用指令

```bash
export JAVA_HOME=/Users/sl/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
GRADLE=~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle

$GRADLE :app:assembleDebug            # 建置
$GRADLE :app:testDebugUnitTest        # JVM 測試（161 條）
$GRADLE :app:connectedDebugAndroidTest # 實機測試（85 條，跑之前先備份）

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell "run-as com.example.eanki cat databases/eanki_user.db" > backup.db
```
