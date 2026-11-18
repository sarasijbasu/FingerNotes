import { compactSdp } from './signal';

export const CHANNEL_LABEL = 'fingernotes';
const STUN = [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun.cloudflare.com:3478' }];

/** Minimal message channel the sync session runs over. */
export interface Channel {
  send(text: string): void;
  onMessage(fn: (text: string) => void): void;
  onClose(fn: () => void): void;
  bufferedAmount(): number;
  close(): void;
}

function waitIceComplete(pc: RTCPeerConnection, timeoutMs = 5000): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const t = setTimeout(resolve, timeoutMs);
    pc.addEventListener('icegatheringstatechange', () => {
      if (pc.iceGatheringState === 'complete') {
        clearTimeout(t);
        resolve();
      }
    });
  });
}

function wrap(dc: RTCDataChannel, pc: RTCPeerConnection): Channel {
  return {
    send: (t) => dc.send(t),
    onMessage: (fn) => dc.addEventListener('message', (e) => fn(String(e.data))),
    onClose: (fn) => {
      dc.addEventListener('close', fn);
      pc.addEventListener('connectionstatechange', () => {
        if (pc.connectionState === 'failed' || pc.connectionState === 'closed') fn();
      });
    },
    bufferedAmount: () => dc.bufferedAmount,
    close: () => {
      dc.close();
      pc.close();
    },
  };
}

const opened = (dc: RTCDataChannel, pc: RTCPeerConnection, timeoutMs = 45_000) =>
  new Promise<Channel>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('Peer did not connect in time')), timeoutMs);
    const ok = () => {
      clearTimeout(t);
      resolve(wrap(dc, pc));
    };
    if (dc.readyState === 'open') ok();
    else dc.addEventListener('open', ok);
  });

/** Air-gapped capable WebRTC peer (non-trickle ICE; SDP fully gathered before export). */
export class Peer {
  readonly pc: RTCPeerConnection;
  constructor(useStun: boolean) {
    this.pc = new RTCPeerConnection({ iceServers: useStun ? STUN : [] });
  }

  /** Initiator: returns offer SDP and a promise for the opened channel. */
  async createOffer(): Promise<{ sdp: string; channel: Promise<Channel> }> {
    const dc = this.pc.createDataChannel(CHANNEL_LABEL, { ordered: true });
    await this.pc.setLocalDescription(await this.pc.createOffer());
    await waitIceComplete(this.pc);
    return { sdp: compactSdp(this.pc.localDescription!.sdp), channel: opened(dc, this.pc) };
  }

  async acceptAnswer(sdp: string) {
    await this.pc.setRemoteDescription({ type: 'answer', sdp });
  }

  /** Responder: returns answer SDP and a promise for the opened channel. */
  async answerOffer(offerSdp: string): Promise<{ sdp: string; channel: Promise<Channel> }> {
    const channel = new Promise<Channel>((resolve, reject) => {
      this.pc.addEventListener('datachannel', (e) => opened(e.channel, this.pc).then(resolve, reject));
    });
    await this.pc.setRemoteDescription({ type: 'offer', sdp: offerSdp });
    await this.pc.setLocalDescription(await this.pc.createAnswer());
    await waitIceComplete(this.pc);
    return { sdp: compactSdp(this.pc.localDescription!.sdp), channel };
  }

  close() {
    this.pc.close();
  }
}
