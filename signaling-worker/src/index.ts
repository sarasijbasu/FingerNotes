/**
 * FingerNotes internet sync helper (Cloudflare Workers free tier). Stores no user data.
 *
 * 1. GET /room/<chainId>   WebSocket relay. Each device on a sync chain joins the room named after its
 *    chainId (32 hex chars derived from the 24 words). Text frames are forwarded to the *other* sockets
 *    in the room. Frames are "FN1:" AES-256-GCM ciphertext, so the relay cannot read SDPs, device
 *    names or data — it only lets devices find each other.
 * 2. GET /turn?room=<chainId>   Short-lived Cloudflare TURN credentials so WebRTC can connect across any
 *    network (mobile data, CGNAT, office firewalls). Traffic through TURN is still end-to-end encrypted
 *    by the DataChannel (DTLS) *and* by the chain key. Needs the TURN_KEY_ID / TURN_KEY_API_TOKEN
 *    secrets; without them it returns STUN only.
 *
 * After the handshake, notes/expenses/media flow device-to-device over the WebRTC DataChannel.
 */
import { DurableObject } from 'cloudflare:workers';

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
  TURN_KEY_ID?: string;
  TURN_KEY_API_TOKEN?: string;
}

const MAX_FRAME = 16 * 1024;
const MAX_PEERS = 8;
const TURN_TTL = 12 * 3600;
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS' };
const STUN_ONLY = [{ urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }];

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS } });

async function turnCredentials(env: Env): Promise<unknown[]> {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return STUN_ONLY;
  const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ttl: TURN_TTL }),
  });
  if (!res.ok) return STUN_ONLY;
  const data = (await res.json()) as { iceServers?: unknown };
  // Newer API returns an array, older one a single object — normalise to an array.
  const servers = Array.isArray(data.iceServers) ? data.iceServers : data.iceServers ? [data.iceServers] : [];
  return servers.length ? servers : STUN_ONLY;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });

    if (url.pathname === '/turn') {
      if (!/^[0-9a-f]{32}$/.test(url.searchParams.get('room') ?? '')) return json({ error: 'room required' }, 400);
      return json({ iceServers: await turnCredentials(env), ttl: TURN_TTL });
    }

    const m = url.pathname.match(/^\/room\/([0-9a-f]{32})$/);
    if (!m) return new Response('FingerNotes signaling relay', { status: 200, headers: CORS });
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('Expected WebSocket', { status: 426 });
    return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(req);
  },
};

export class Room extends DurableObject {
  async fetch(_req: Request): Promise<Response> {
    if (this.ctx.getWebSockets().length >= MAX_PEERS) return new Response('Room full', { status: 429 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer) {
    if (typeof msg !== 'string' || msg.length > MAX_FRAME || !msg.startsWith('FN1:')) return;
    for (const peer of this.ctx.getWebSockets()) if (peer !== ws) peer.send(msg);
  }

  async webSocketClose(ws: WebSocket, code: number) {
    ws.close(code, 'bye');
  }
}
