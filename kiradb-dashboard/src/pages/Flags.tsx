import { useApi } from '../useApi';
import { Card, Chip, Status, Table } from '../components/ui';

interface FlagStats {
  enabledImpressions: number;
  disabledImpressions: number;
  enabledConversions: number;
  disabledConversions: number;
  enabledConversionRate: number;
  disabledConversionRate: number;
}

interface Flag {
  name: string;
  killed: boolean;
  rolloutPercent: number;
  enabled: boolean;
  stats: FlagStats;
}

function rate(r: number): string {
  return r < 0 ? '—' : `${(r * 100).toFixed(1)}%`;
}

export default function FlagsPage() {
  const { data, error, loading } = useApi<Flag[]>('/api/flags');
  const flags = data ?? [];
  return (
    <Card title="Feature Flags">
      <Status loading={loading} error={error} />
      {flags.length === 0 && !loading && !error && (
        <div className="text-sm text-zinc-500">No flags defined. Create one with FLAG.SET.</div>
      )}
      {flags.length > 0 && (
        <Table
          headers={[
            'Flag', 'State', 'Rollout %',
            'Impressions (on / off)', 'Conversions (on / off)', 'Conv. rate (on / off)',
          ]}
        >
          {flags.map((f) => (
            <tr key={f.name} className="text-zinc-300">
              <td className="px-3 py-2 font-mono text-zinc-100">{f.name}</td>
              <td className="px-3 py-2">
                {f.killed ? <Chip tone="warn">killed</Chip> : f.enabled ? <Chip tone="ok">active</Chip> : <Chip tone="off">off</Chip>}
              </td>
              <td className="px-3 py-2">{(f.rolloutPercent * 100).toFixed(0)}%</td>
              <td className="px-3 py-2">
                {f.stats.enabledImpressions.toLocaleString()} / {f.stats.disabledImpressions.toLocaleString()}
              </td>
              <td className="px-3 py-2">
                {f.stats.enabledConversions.toLocaleString()} / {f.stats.disabledConversions.toLocaleString()}
              </td>
              <td className="px-3 py-2">
                {rate(f.stats.enabledConversionRate)} / {rate(f.stats.disabledConversionRate)}
              </td>
            </tr>
          ))}
        </Table>
      )}
    </Card>
  );
}
