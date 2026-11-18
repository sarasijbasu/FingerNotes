/** Hybrid Logical Clock — see docs/PROTOCOL.md §3. */
export interface HlcParts {
  ms: number;
  counter: number;
  node: string;
}

export const formatHlc = (p: HlcParts) =>
  `${String(p.ms).padStart(15, '0')}-${String(Math.min(p.counter, 99999)).padStart(5, '0')}-${p.node}`;

export function parseHlc(s: string): HlcParts {
  const [ms, counter, node] = s.split('-');
  return { ms: Number(ms), counter: Number(counter), node };
}

export const compareHlc = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

export class Hlc {
  private ms = 0;
  private counter = 0;
  constructor(public readonly node: string, last?: string, private readonly now: () => number = Date.now) {
    if (last) {
      const p = parseHlc(last);
      this.ms = p.ms;
      this.counter = p.counter;
    }
  }

  tick(): string {
    const wall = this.now();
    if (wall > this.ms) {
      this.ms = wall;
      this.counter = 0;
    } else {
      this.counter++;
    }
    return this.current();
  }

  receive(remote: string): void {
    const r = parseHlc(remote);
    const wall = this.now();
    const max = Math.max(wall, this.ms, r.ms);
    if (max === this.ms && max === r.ms) this.counter = Math.max(this.counter, r.counter) + 1;
    else if (max === this.ms) this.counter++;
    else if (max === r.ms) this.counter = r.counter + 1;
    else this.counter = 0;
    this.ms = max;
  }

  current(): string {
    return formatHlc({ ms: this.ms, counter: this.counter, node: this.node });
  }
}
