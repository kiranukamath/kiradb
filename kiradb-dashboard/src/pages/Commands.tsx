import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useApi } from '../useApi';
import { Card, Status, Table } from '../components/ui';

interface CommandMetric {
  name: string;
  count: number;
  errors: number;
  avgMicros: number;
  p50Micros: number;
  p95Micros: number;
  p99Micros: number;
}

export default function CommandsPage() {
  const { data, error, loading } = useApi<CommandMetric[]>('/api/commands');
  const metrics = data ?? [];

  return (
    <div className="space-y-4">
      <Card title="Command Throughput & p95 Latency">
        <Status loading={loading} error={error} />
        {metrics.length === 0 && !loading && !error && (
          <div className="text-sm text-zinc-500">No commands executed yet.</div>
        )}
        {metrics.length > 0 && (
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={metrics}>
              <CartesianGrid strokeDasharray="3 3" stroke="#27272a" />
              <XAxis dataKey="name" stroke="#71717a" fontSize={12} />
              <YAxis yAxisId="count" stroke="#34d399" fontSize={12} />
              <YAxis yAxisId="latency" orientation="right" stroke="#818cf8" fontSize={12} />
              <Tooltip
                contentStyle={{ background: '#18181b', border: '1px solid #3f3f46', borderRadius: 8 }}
                labelStyle={{ color: '#e4e4e7' }}
              />
              <Legend />
              <Bar yAxisId="count" dataKey="count" name="calls" fill="#34d399" radius={[3, 3, 0, 0]} />
              <Bar yAxisId="latency" dataKey="p95Micros" name="p95 (µs)" fill="#818cf8" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </Card>

      <Card title="Per-command Detail">
        <Table headers={['Command', 'Count', 'Errors', 'Avg µs', 'p50 µs', 'p95 µs', 'p99 µs']}>
          {metrics.map((m) => (
            <tr key={m.name} className="text-zinc-300">
              <td className="px-3 py-2 font-mono text-zinc-100">{m.name}</td>
              <td className="px-3 py-2">{m.count.toLocaleString()}</td>
              <td className={`px-3 py-2 ${m.errors > 0 ? 'text-red-400' : ''}`}>{m.errors.toLocaleString()}</td>
              <td className="px-3 py-2">{m.avgMicros.toLocaleString()}</td>
              <td className="px-3 py-2">{m.p50Micros.toLocaleString()}</td>
              <td className="px-3 py-2">{m.p95Micros.toLocaleString()}</td>
              <td className="px-3 py-2">{m.p99Micros.toLocaleString()}</td>
            </tr>
          ))}
        </Table>
      </Card>
    </div>
  );
}
