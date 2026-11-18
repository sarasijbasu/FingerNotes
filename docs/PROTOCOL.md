# FingerNotes Cross-Platform Contract (v1)

This document is the single source of truth that the Android (Kotlin) and Chrome
(TypeScript) clients both implement. Anything that crosses a device boundary — sync
messages, QR payloads, backup archives, spreadsheets — is defined here. Both test
suites check themselves against `shared/test-vectors.json`.

## 1. Primitives

| Primitive | Definition |
|---|---|
| UTF-8 | All strings are UTF-8. |
| `b64url` | Base64url, no padding (RFC 4648 §5). |
| `hex` | Lowercase hexadecimal. |
| AES-GCM | AES-256-GCM, 12-byte random IV, 128-bit tag, **no AAD**. |
| Envelope | `"v1." + b64url(iv ‖ ciphertext ‖ tag)` |
| PBKDF2 | PBKDF2-HMAC-SHA256 |
| deflate | Raw DEFLATE (RFC 1951, no zlib header) — `CompressionStream('deflate-raw')` / `Deflater(level, nowrap = true)` |

## 2. Sync chain key derivation

* Mnemonic: BIP39, English wordlist (`shared/bip39-english.txt`), **24 words** (256-bit entropy), checksum validated.
* Normalisation: trim, lower-case, collapse whitespace to single spaces, Unicode NFKD.
* `root = PBKDF2(password = UTF8(normalised mnemonic), salt = UTF8("fingernotes/sync/v1"), iterations = 200000, dkLen = 64)`
* `syncKey = root[0..32]` → AES-256-GCM key for every sync/signaling payload.
* `chainId = hex(SHA-256(root[32..64]))[0..32]` — public, non-secret room identifier (signaling).

## 3. Hybrid Logical Clock (HLC)

String form, lexicographically sortable:

```
{physical ms, 15 digits zero-padded}-{counter, 5 digits zero-padded}-{nodeId, 16 hex}
000001763000000000-00000-a1b2c3d4e5f60718
```

* `tick()` on every local mutation: `ms' = max(wall, ms)`; counter resets to 0 when `ms'` advances, else increments.
* `receive(remote)` merges like the standard HLC algorithm (Kulkarni et al.).
* Comparison is plain string comparison. Ties are impossible across devices because nodeIds differ.

## 4. Records (wire JSON)

All timestamps are Unix epoch milliseconds (numbers). Every syncable record carries `id` (UUID v4), `createdAt`, `updatedAt`, `hlc`.

```jsonc
// Note
{ "id": "…", "kind": "text" | "checklist", "title": "", "body": "markdown-lite",
  "items": [ { "id": "…", "text": "", "checked": false, "position": 0 } ],
  "color": "default|red|orange|yellow|green|teal|blue|purple|pink|brown|gray",
  "labelIds": ["…"],
  "attachments": [ { "id": "…", "kind": "image" | "audio", "mime": "image/jpeg", "size": 1234, "name": "x.jpg", "createdAt": 0 } ],
  "isPinned": false, "isArchived": false, "isHidden": false,
  "isTrashed": false, "deletedAt": null,
  "createdAt": 0, "updatedAt": 0, "hlc": "…" }

// Label
{ "id": "…", "name": "Work", "createdAt": 0, "updatedAt": 0, "hlc": "…" }

// Expense
{ "id": "…", "amount": 12.5, "currency": "₹", "title": "Cafe", "categoryId": "food",
  "paymentMethod": "CASH" | "CARD" | "UPI_BANK", "occurredAt": 0, "notes": "",
  "receipt": Attachment | null, "isTrashed": false, "deletedAt": null,
  "createdAt": 0, "updatedAt": 0, "hlc": "…" }

// Category (built-ins have fixed ids: food, transport, bills, shopping, health)
{ "id": "food", "name": "Food", "color": "#F4511E", "builtIn": true, "createdAt": 0, "updatedAt": 0, "hlc": "…" }

// SyncTombstone — permanent deletion marker, never removed
{ "id": "<uuid of purged record>", "entityType": "note" | "expense" | "label" | "category", "purgedAt": 0, "hlc": "…" }
```

**Body "markdown-lite"** (rendered identically on both clients): `**bold**`, `*italic*`, lines starting with `- ` are bullets, `http(s)://…` is a clickable link.

Checklist items are stored in their own table on both platforms (Room `checklist_items`, Dexie `checklistItems`) but travel embedded in their note; any item change bumps the note's `hlc`.

## 5. Merge rules

1. If a tombstone exists for `id` → the record is dropped; tombstones always win.
2. Else if no local record, or `incoming.hlc > local.hlc` → incoming replaces local (Last-Write-Wins on the whole record).
3. Incoming tombstone → store it, delete the record, its checklist items and its media.
4. Every observed HLC is passed through `receive()`.

Trash/restore/hide/archive are ordinary field edits, so they merge through rule 2. Permanent purge (manual "Delete forever" or the 30-day job: `now - deletedAt >= 30 days`) writes a tombstone so peers purge too.

## 6. Sync session (WebRTC DataChannel)

* DataChannel label `"fingernotes"`, ordered + reliable. Every message is a **text** frame containing an Envelope of the UTF-8 JSON message, encrypted with `syncKey`.
* Sequence (both peers run it symmetrically):

```
→ {"t":"hello","v":1,"deviceId","deviceName","platform":"android"|"chrome"}
→ {"t":"manifest","notes":{id:hlc},"labels":{…},"expenses":{…},"categories":{…},"tombstones":[id…],"media":[id…]}
   (on peer manifest)
→ {"t":"records","notes":[…],"labels":[…],"expenses":[…],"categories":[…],"tombstones":[…]}
→ {"t":"media","id","ownerId","mime","seq","total","data":b64url}   (32 KiB raw per chunk, only ids the peer lacks)
→ {"t":"done"}
```
A session completes when a peer has both sent and received `done`.

## 7. Signaling payloads (QR / text / relay)

* Pairing QR: `FNPAIR:` + normalised 24-word mnemonic.
* Offer / answer: `FN1:` + Envelope( deflate( UTF8( JSON `{ "type":"offer"|"answer"|"announce", "sdp"?, "deviceId", "deviceName", "platform" }` ))) with `syncKey`.
  SDP is non-trickle (ICE gathering completed before encoding); TCP candidates are stripped to fit QR version 40-L.
* Signal JSON may carry `"to": "<deviceId>"`. A peer ignores any signal whose `to` is present and not its own deviceId. `to` absent or `null` means broadcast.

### 7.1 Internet sync (pure device-to-device)

* **Room:** WebSocket `wss://<worker>/room/<chainId>`. The worker forwards `FN1:` frames to the other sockets in the room and stores nothing. Clients reconnect with exponential backoff (max 30 s).
* **ICE:** `GET https://<worker>/turn?room=<chainId>` → `{ "iceServers": [ { "urls": [...], "username"?, "credential"? } ], "ttl" }`. These are short-lived Cloudflare TURN credentials (STUN only if the worker has no TURN key). Clients add them to their own STUN list. Payloads stay end-to-end encrypted (DTLS + chain key) even when relayed through TURN.
* **Discovery and pairing**, with `me` = own deviceId:
  * On connect, every 4 min, 3 s after a local edit, and on pull-to-refresh: broadcast `announce`.
  * On a broadcast `announce` from `X`: if `me > X`, send a directed `offer` to `X`; else reply with a directed `announce` to `X`.
  * On a directed `announce` from `X` with `me > X`: send a directed `offer` (never reply to a directed announce, so there are no loops).
  * On `offer` from `X`: answer with a directed `answer`, unless a session is already running (the offerer retries on the next announce).
  * An offer without an answer is dropped after 30 s; a channel that doesn't open is dropped after 45 s.
* Once the DataChannel opens, the session of §6 runs unchanged. One session at a time per device. Changes written by a session do not trigger a new announce (2.5 s quiet period).
* Both devices must be online at the same time; nothing is stored off-device.

## 8. Encrypted backup (`.zip`)

WinZip AES-256 (AE-2) on every entry — Zip4j `AesKeyStrength.KEY_STRENGTH_256` / zip.js `encryptionStrength: 3`.

```
metadata.json   { "app":"FingerNotes", "appVersion", "schemaVersion":1, "createdAt", "platform",
                  "counts": {…}, "checksums": { "<path>": "<sha256 hex>" } }   // every other entry
notes.json      { "notes":[Note…], "labels":[Label…] }
expenses.json   { "expenses":[Expense…], "categories":[Category…] }
tombstones.json { "tombstones":[Tombstone…] }
media/index.json  { "media": [ { "id", "ownerId", "mime" } ] }
media/<attachmentId>   raw bytes
```
Restore: decrypt → verify every checksum → **Merge by UUID** (rules of §5) or **Overwrite** (wipe, then insert).

## 9. Excel export

Sheet `Expenses`, columns: `Date | Time | Title / Merchant | Category | Payment Method | Currency | Amount | Notes`.
Bold filled header row, Date as Excel date (`yyyy-mm-dd`), Time as Excel time (`hh:mm`), Amount `#,##0.00`,
final row `TOTAL` in column F and `=SUM(G2:G{n+1})` in column G. Rows sorted by `occurredAt` ascending.
Payment method labels: `Cash`, `Card`, `UPI/Bank`.
