import type { ReactNode } from 'react';

/** Renders the shared "markdown-lite" body format without innerHTML (docs/PROTOCOL.md §4). */
const INLINE = /(\*\*[^*\n]+\*\*|\*[^*\n]+\*|https?:\/\/[^\s<>()]+)/g;

function inline(text: string, key: string): ReactNode[] {
  return text.split(INLINE).map((part, i) => {
    const k = `${key}-${i}`;
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) return <b key={k}>{part.slice(2, -2)}</b>;
    if (part.startsWith('*') && part.endsWith('*') && part.length > 2) return <i key={k}>{part.slice(1, -1)}</i>;
    if (/^https?:\/\//.test(part))
      return (
        <a key={k} href={part} target="_blank" rel="noreferrer noopener" onClick={(e) => e.stopPropagation()}>
          {part}
        </a>
      );
    return part;
  });
}

export function Markdown({ text }: { text: string }) {
  const out: ReactNode[] = [];
  let bullets: string[] = [];
  const flush = (k: number) => {
    if (bullets.length) {
      out.push(
        <ul key={`ul${k}`}>
          {bullets.map((b, i) => (
            <li key={i}>{inline(b, `li${k}-${i}`)}</li>
          ))}
        </ul>,
      );
      bullets = [];
    }
  };
  text.split('\n').forEach((line, i) => {
    if (/^\s*[-•]\s+/.test(line)) bullets.push(line.replace(/^\s*[-•]\s+/, ''));
    else {
      flush(i);
      if (line.trim()) out.push(<p key={i}>{inline(line, `p${i}`)}</p>);
    }
  });
  flush(-1);
  return <div className="md">{out}</div>;
}
