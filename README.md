# FingerNotes

Offline-first, zero-server personal suite: **Google Keep-style notes + daily expense tracker**, encrypted at rest, with **Brave-style peer-to-peer sync** (24-word chain, WebRTC) and **cross-compatible AES-256 ZIP backups**.

| Client | Stack |
|---|---|
| `android/` | Kotlin · Jetpack Compose Material 3 (dynamic color, dark/light) · MVVM (`StateFlow`) · Room + **SQLCipher** · Android Keystore · BiometricPrompt · WorkManager · **Zip4j** · **FastExcel** · **Stream WebRTC Android** · ZXing |
| `extension/` | Manifest V3 · React + TypeScript (Vite) · **Side Panel**, popup, full-tab app, context menu · Dexie/IndexedDB encrypted with **Web Crypto AES-256-GCM** · WebAuthn (PRF) / PIN · `chrome.alarms` · **@zip.js/zip.js** · SheetJS (`xlsx-js-style`) · native `RTCPeerConnection` |
| `signaling-worker/` | *Optional* Cloudflare Worker (free tier) WebSocket relay for automatic pairing. Sees only ciphertext. |
| `shared/` | BIP39 wordlist, cross-platform **test vectors** and a zip.js backup fixture both test suites verify against |
| `docs/PROTOCOL.md` | **The contract**: key derivation, envelope format, HLC, record JSON, merge rules, sync messages, QR payloads, backup layout, xlsx columns |

The two clients share one *specification* (not one binary): every byte that crosses a device boundary is defined in `docs/PROTOCOL.md`, and both implementations are tested against the same vectors.

## Repository layout

```
docs/PROTOCOL.md               cross-platform contract (start here)
shared/                        bip39-english.txt · test-vectors.json · fixtures/web-backup.zip
extension/
  public/manifest.json         MV3: side_panel, service worker, contextMenus, alarms
  src/core/                    crypto · mnemonic · hlc · model · merge · db (Dexie) · vault · repo · backup · xlsx · purge · inbox
  src/sync/                    signal (QR/relay codec) · peer (RTCPeerConnection) · session (sync protocol)
  src/ui/                      Lock · Notes (masonry) · Editor · Expenses · Trash · Sync · Settings · Popup
  src/background.ts            daily purge alarm, "Save selection to Notes", side-panel launcher
  test/                        engine/backup/xlsx tests · interop tests · vector generator
android/app/src/main/java/app/fingernotes/
  core/                        Crypto · Mnemonic(BIP39) · Hlc · Model · Merge · Backup(Zip4j) · XlsxExporter(FastExcel) · Signal
  data/                        db/Database.kt (Room entities + DAOs + SQLCipher) · KeyManager (Keystore) · Repository · Settings
  sync/                        PeerManager (WebRTC) · SyncSession · RelaySignaling (OkHttp)
  ui/                          MainActivity · AppViewModel · Lock/Notes/Editor/Expenses/Trash/Sync/Settings screens
  FingerNotesApp.kt            DI container + TrashPurgeWorker (WorkManager, daily)
android/app/src/test/          CrossPlatformTest — runs the shared vectors, opens the zip.js fixture, writes a Zip4j backup for Chrome
signaling-worker/              optional relay (wrangler deploy)
.github/workflows/             android.yml (tests → APK, then Chrome-opens-Android-backup interop job) · extension.yml (tests → zip/crx)
```

## Build & run

### Chrome extension
```bash
cd extension
npm install
npm test          # engine, backup, xlsx, interop vectors
npm run build     # → extension/dist
npm run package   # → fingernotes-extension-1.0.0.zip
```
Load it: `chrome://extensions` → Developer mode → **Load unpacked** → `extension/dist`. Click the toolbar icon for the popup, use **Side panel**/**Full app**, or right-click selected text → *Save selection to Notes*. Camera QR scanning and voice memos need the **full-tab app** (side panels can't show permission prompts).

### Android
Open `android/` in Android Studio (Ladybug or newer, JDK 17) and run, or from the CLI with Gradle 8.11:
```bash
cd android
gradle :app:testDebugUnitTest :app:assembleDebug
```
The repo ships `gradle-wrapper.properties` but not the wrapper JAR; run `gradle wrapper` once (or let Android Studio do it) if you prefer `./gradlew`. minSdk 26, targetSdk 35.

### CI (GitHub Actions)
* `extension.yml` → tests, build, `.zip` artifact; `.crx` too when a `CRX_PRIVATE_KEY` secret exists.
* `android.yml` → unit tests (including the Chrome vectors), debug + release `.apk` (signed when `FN_KEYSTORE_B64`, `FN_KEYSTORE_PASSWORD`, `FN_KEY_ALIAS`, `FN_KEY_PASSWORD` secrets exist), then an **interop** job where the extension opens the backup Zip4j just wrote.

### Releases
Push a version tag and `release.yml` builds the APK and the extension (`.zip`, plus `.crx` if `CRX_PRIVATE_KEY` is set), stamps both with the version, and publishes a GitHub Release:
```bash
git tag v1.0.1
git push origin v1.0.1
```
The Android `versionCode` is `major*10000 + minor*100 + patch`. Add the `FN_KEYSTORE_*` secrets before your first real release. Without them every release is signed with a different throwaway key, so a new APK can't install over the old one.

### Optional relay
```bash
cd signaling-worker && npm install && npx wrangler deploy
```
Paste the worker URL into *Settings → Relay URL* on each device. Leave it empty for the 100% offline QR handshake.

## How it works

**Security model**
* *Chrome*: PIN/password → PBKDF2-SHA256 (310k) → KEK → unwraps a random 256-bit DEK. Every IndexedDB row is an AES-GCM envelope; only ids, foreign keys and `deletedAt` stay plaintext (so the service worker can purge while locked). WebAuthn unlock uses the **PRF extension** to wrap the same DEK (offered only when the authenticator supports PRF). With a 1 or 5 minute timeout, the DEK sits in `chrome.storage.session` (RAM only) so the popup, side panel and tab share one unlock. *Immediately* keeps it in page memory only.
* *Android*: SQLCipher passphrase and media-file key come from a random secret wrapped by a non-exportable **AndroidKeyStore** AES key. PIN (PBKDF2 verifier) + **BiometricPrompt** gate the UI. `FLAG_SECURE` is applied on lock screens and in the Hidden vault. Cloud backup and device transfer are disabled.
* *Context-menu clips while locked*: encrypted to an RSA-OAEP inbox public key whose private half is sealed under the DEK, then imported on the next unlock.
* *Hidden vault*: hidden notes are excluded from the grid, search and popup, and need a second PIN (or biometrics on Android). They share the main encryption key so they still sync and back up.

**Sync**: The 24 words → PBKDF2 → `syncKey` (AES-256-GCM) + public `chainId`. Pairing happens either by QR/copy-paste (offer QR ⇄ answer QR; non-trickle ICE so one QR each way) or through the optional relay. Peers then exchange manifests (`id → HLC`), send only newer records plus tombstones, and stream missing media in 32 KiB chunks. Merge is HLC last-write-wins per record, and **tombstones always win**, so purges never resurrect.

**Trash**: Deleting sets `isTrashed`/`deletedAt`, which syncs like any edit. A daily job (`WorkManager` / `chrome.alarms`, plus a catch-up on unlock) hard-deletes anything trashed 30 or more days ago and writes a `SyncTombstone`.

**Backup**: WinZip AES-256 on every entry: `metadata.json` (with per-file SHA-256), `notes.json`, `expenses.json`, `tombstones.json`, `media/index.json`, `media/<id>`. Restore verifies all checksums, then **Merge by UUID** (same rules as sync) or **Overwrite**.

## Verification status

* ✅ Extension: `npm test` passes (sync convergence incl. trash/restore/purge, 30-day purge tombstones, AES-256 ZIP round-trip + wrong password, xlsx SUM formula, shared vectors). `tsc` is clean and the build is verified. The UI was smoke-tested in a browser: setup, unlock, note creation, markdown rendering, every route with no console errors.
* ⚠️ Android: written against the APIs of the pinned library versions, but **not yet compiled on this machine** (no JDK here). The first CI run (or Android Studio) is the compile check. `CrossPlatformTest` then proves key derivation, envelopes, signaling, Zip4j⇄zip.js and xlsx output match Chrome.
* Not verified on real hardware yet: a live Android⇄Chrome WebRTC session. NAT note: Chrome hides LAN IPs behind mDNS names unless camera permission is granted. That is why the full-tab app (which asks for the camera to scan QR) and the optional STUN servers help connectivity.

## Known gaps / next steps
* No home-screen widgets yet. The "Hide Note excludes from widgets" rule therefore applies trivially.
* The rich-text format is a shared markdown-lite (`**bold**`, `*italic*`, `- bullets`, auto-links) rather than full HTML, so both platforms render it identically.
* Custom-category colors are random. Label and category renames are LWW like everything else.
