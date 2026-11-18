/**
 * FingerNotes optional signaling relay (Cloudflare Workers free tier).
 *
 * - Route: GET /room/<chainId>  (WebSocket upgrade). chainId = 32 hex chars derived from the mnemonic.
 * - The relay forwards every text frame to the *other* sockets in the same room. Frames are
 *   "FN1:" AES-256-GCM ciphertext — the relay cannot read SDPs, device names or data.
 * - Nothing is persisted. User data never touches this worker: it only helps two peers find each
 *   other, after which everything flows over the direct WebRTC DataChannel.
 */
import { DurableObject } from 'cloudflare:workers';

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
}

const MAX_FRAME = 16 * 1024;
const MAX_PEERS = 8;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const m = url.pathname.match(/^\/room\/([0-9a-f]{32})$/);
    if (!m) return new Response('FingerNotes signaling relay', { status: 200 });
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
