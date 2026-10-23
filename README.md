# AladdinDashCam

Android 原生 Kotlin 多品牌 Wi-Fi 行車記錄器 App，提供即時預覽、裝置相簿、下載、騎乘資訊與設定。以能力與協定適配不同機型，不限 B1；目前硬體診斷主要來自 B1／Generalplus，不能據此宣稱所有 Generalplus 或 Novatek 裝置相容。

## 接手入口

- [最新進度與接手步驟](docs/AI_AGENT_HANDOFF_2026-10-22_PLAYBACK.md)：目前程式、實測證據、尚未完成項目及操作順序。
- [產品與技術需求規格書](AladdinDashCam-產品與技術需求規格書.md)：需求與原始驗收標準。
- [相簿與橫向問題審查](docs/AI_AGENT_REVIEW_2026-10-22_ALBUM_PLAYBACK_AND_LANDSCAPE.md)：歷次診斷；最新狀態以接手文件為準。

## 目前狀態（2026-10-22）

已修正真實列檔的日期／大小解析、分頁、排序、相簿來源與橫向排版，並加入使用中預覽快取保護。B1 錄影檔實際使用 JPEG／UDP，已新增專用回放接收與影格重組，不再用直播 H.264 冒充回放。

21:52 的實測版可顯示兩段不同錄影的連續 JPEG 影格，切檔後畫面內容確實改變，並收到 PCMA 音訊封包。退出時遇到 B1 控制埠逾時與 Wi-Fi 消失，**錄影恢復未驗收通過**；22:02 最新版與縮圖、照片、精確 seek 等仍待實機驗證。目前不是全功能完成版本。詳見接手文件第 8 節。

## 開發環境與建置

- JDK 17、Android SDK 35；最低 Android 8.0（API 26）。
- Application ID：`com.aladdin.dashcam`；目前設定版本 `1.0.1`（versionCode 2）。
- 使用 repository 的 Gradle Wrapper，或 Android Studio 開啟專案。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。2026-10-22 22:02 的建置成功，17 suites、99 tests、0 failures、0 errors，已安裝到測試手機。影格與切檔實測使用較早的 21:52 版；最新版本尚待 B1 恢復 Wi-Fi 後重驗。

## Maps 與簽署設定

依 `secrets.properties.example` 建立根目錄 `secrets.properties`，填入 `MAPS_API_KEY`；建置亦接受同名環境變數。不要將真實金鑰、簽署密碼或 keystore 提交。Release 簽署參數見範例檔，未設定時不能當作已具備上架產物。

## 實機工作原則

先閱讀 `AGENTS.md` 與接手文件。確認記錄器供電、手機實際連到其 Wi-Fi；原廠 App 與本 App 不可同時搶占控制／串流。8081 維持單一控制連線，通訊 socket 使用正確 Android Network。

不得自行執行格式化、恢復原廠、修改 Wi-Fi 名稱／密碼，亦不得自行 stage、commit、push 或更動使用者暫存區。遠端格式化入口已實作不代表真實抹除已驗證；圖片、截圖與測試目錄依現有忽略規則保留於本機。
