import type { ReactNode } from 'react';

/** Card container with title — the basic layout unit of every page. */
export function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-zinc-800 bg-zinc-900/60 p-5">
      <h2 className="mb-4 text-sm font-semibold uppercase tracking-wider text-zinc-400">{title}</h2>
      {children}
    </section>
  );
}

/** Big-number stat tile. */
export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg bg-zinc-800/50 p-4">
      <div className="text-xs text-zinc-400">{label}</div>
      <div className="mt-1 text-2xl font-semibold text-zinc-50">{value}</div>
      {hint && <div className="mt-1 text-xs text-zinc-500">{hint}</div>}
    </div>
  );
}

/** Small colored status chip. */
export function Chip({ tone, children }: { tone: 'ok' | 'warn' | 'off'; children: ReactNode }) {
  const tones = {
    ok: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
    warn: 'bg-red-500/15 text-red-400 border-red-500/30',
    off: 'bg-zinc-500/15 text-zinc-400 border-zinc-500/30',
  } as const;
  return (
    <span className={`inline-block rounded-full border px-2 py-0.5 text-xs font-medium ${tones[tone]}`}>
      {children}
    </span>
  );
}

/** Loading / error banner shown while a page's data is unavailable. */
export function Status({ loading, error }: { loading: boolean; error: string | null }) {
  if (loading) return <div className="text-sm text-zinc-500">Loading…</div>;
  if (error) {
    return (
      <div className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400">
        Cannot reach KiraDB HTTP API: {error}
      </div>
    );
  }
  return null;
}

/** Dark-themed table shell. */
export function Table({ headers, children }: { headers: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-zinc-800 text-xs uppercase tracking-wider text-zinc-500">
            {headers.map((h) => (
              <th key={h} className="px-3 py-2 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-800/60">{children}</tbody>
      </table>
    </div>
  );
}
