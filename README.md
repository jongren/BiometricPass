# BiometricPass — 純離線生物辨識密碼管理員

**BiometricPass** 是一款專為 Android 打造的 **100% 純離線、零網路權限** 生物辨識密碼管理工具。日常使用完全免背主密碼，透過 Android 硬體安全晶片（`AndroidKeyStore` TEE / StrongBox）搭配系統生物辨識（指紋／臉部／螢幕鎖定）保護所有帳號與密碼。

---

## 核心特色 (Key Features)

- **🛡️ 100% 物理斷網・零網路權限**：於 `AndroidManifest.xml` 強制移除 `INTERNET`、`ACCESS_NETWORK_STATE` 與 `ACCESS_WIFI_STATE`，並關閉系統雲端自動備份（`android:allowBackup="false"`），帳密永遠只留在手機本機。
- **👆 免背主密碼・硬體信封加密**：採用 **AES-256-GCM 信封加密（Envelope Encryption）**，資料加密金鑰（DEK）由 `AndroidKeyStore` 硬體金鑰包裹保護；新增系統指紋不會導致金鑰失效，並支援手機螢幕鎖定（圖形／PIN 碼）無縫備援。
- **⚡ 原生鍵盤自動填入 ＋ 雙重突破封鎖召喚**：
  - 支援 Android 11+ 鍵盤內嵌建議晶片（`InlineSuggestions`）與 Android 10 下拉自動填入選單。
  - 針對封鎖 Autofill 的金融或特殊 App，提供 **快速設定控制中心磚塊（Quick Settings Tile）** 與 **文字長按選單（`ACTION_PROCESS_TEXT`）** 雙重召喚填入。
- **🔒 防釣魚與防偽冒保護**：
  - 內嵌 WebView 網域隔離，防止第三方 App 內建瀏覽器竊取網站密碼。
  - 內建離線公開後綴清單（Public Suffix List）精準比對網域。
  - 目標 App 開發者簽章雜湊（SHA-256）綁定驗證，防範同名偽冒程式。
- **📱 無痛離線備份與換機**：
  - 支援匯出／匯入 AES-256-GCM 加密 `.bpass` 備份檔與備份解鎖金鑰 QR 碼。
  - 支援雙機離線加密 QR 碼與一次性傳輸碼快速換機，以及匯入主流瀏覽器／Bitwarden CSV 密碼檔。

---

## 技術規格 (Tech Stack)

- **語言與介面**：Kotlin, Jetpack Compose (Material 3)
- **系統支援**：`minSdk = 29` (Android 10) / `targetSdk = 36` (Android 16)
- **本機儲存與安全**：Room Database, Android Keystore (`AES/GCM/NoPadding` 256-bit), `BiometricPrompt`
- **離線條碼處理**：CameraX + ZXing (100% On-Device)

---

## 隱私權政策 (Privacy Policy)

- 雙語隱私權政策頁面：[`docs/index.html`](docs/index.html)
- 聯絡信箱：[jongren@gmail.com](mailto:jongren@gmail.com)
