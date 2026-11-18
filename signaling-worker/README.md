# FingerNotes sync helper (Cloudflare Worker)

This worker is what lets your devices sync **over the internet without being on the same network**. It never sees or stores your notes. It only:

- lets devices on the same sync chain find each other (an encrypted WebSocket "room" per chain), and
- hands out short-lived **TURN** credentials, so the direct WebRTC connection works on mobile data, CGNAT and firewalled networks.

Everything is free-tier.

## One-time setup (about 10 minutes)

1. **Create a free Cloudflare account** at https://dash.cloudflare.com/sign-up.
2. **Create a TURN key:** in the dashboard open **Realtime → TURN Server → Create**. Copy the **Turn Token ID** and the **API Token**.
3. **Deploy** from this folder:
   ```bash
   npm install
   npx wrangler login
   npx wrangler secret put TURN_KEY_ID          # paste the Turn Token ID
   npx wrangler secret put TURN_KEY_API_TOKEN   # paste the API Token
   npx wrangler deploy
   ```
   Wrangler prints your URL, e.g. `https://fingernotes-signal.<you>.workers.dev`.
4. **On every device:** FingerNotes → **Settings → Relay URL** → paste that URL. Use the same sync chain (24 words) on each device.

Devices now sync automatically whenever they're open at the same time. On Android, pull down on the notes list to sync immediately; in Chrome, pull down or click the sync icon.

Skipping step 2 still works on most home and office networks (STUN only), but mobile data and CGNAT networks then often fail to connect.

## Check it
- `https://<your-worker>/turn?room=00000000000000000000000000000000` should return JSON with `iceServers`. It includes `turn:` entries when the TURN secrets are set.
