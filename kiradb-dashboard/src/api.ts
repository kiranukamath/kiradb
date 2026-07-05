/**
 * API base URL resolution.
 *
 * - Dev (`npm run dev`): empty base — requests go to the Vite dev server, which
 *   proxies /api/* to the KiraDB HTTP port (see vite.config.ts).
 * - Production build: defaults to http://localhost:8080; override at build time
 *   with VITE_KIRADB_API=http://host:port. The KiraDB HTTP API sends
 *   Access-Control-Allow-Origin: * so cross-origin calls work either way.
 */
export const API_BASE: string = import.meta.env.VITE_KIRADB_API ?? (import.meta.env.DEV ? '' : 'http://localhost:8080');

/** Fetch a JSON endpoint relative to the API base. Throws on non-2xx. */
export async function fetchJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new Error(`${path} → HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}
