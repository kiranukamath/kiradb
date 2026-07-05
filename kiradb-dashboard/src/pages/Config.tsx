import { useApi } from '../useApi';
import { Card, Status, Table } from '../components/ui';

interface ConfigEntry {
  scope: string;
  key: string;
  value: string;
  version: number;
  timestampMillis: number;
}

export default function ConfigPage() {
  const { data, error, loading } = useApi<ConfigEntry[]>('/api/config/scopes');
  const entries = data ?? [];
  return (
    <Card title="Config Store">
      <Status loading={loading} error={error} />
      {entries.length === 0 && !loading && !error && (
        <div className="text-sm text-zinc-500">No config entries. Create one with CFG.SET.</div>
      )}
      {entries.length > 0 && (
        <Table headers={['Scope', 'Key', 'Latest value', 'Version', 'Last changed']}>
          {entries.map((e) => (
            <tr key={`${e.scope}:${e.key}`} className="text-zinc-300">
              <td className="px-3 py-2 font-mono text-indigo-300">{e.scope}</td>
              <td className="px-3 py-2 font-mono text-zinc-100">{e.key}</td>
              <td className="px-3 py-2 font-mono">{e.value}</td>
              <td className="px-3 py-2">v{e.version}</td>
              <td className="px-3 py-2 text-zinc-400">
                {new Date(e.timestampMillis).toLocaleString()}
              </td>
            </tr>
          ))}
        </Table>
      )}
    </Card>
  );
}
