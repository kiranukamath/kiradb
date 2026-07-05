import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { useApi } from '../useApi';
import { Card, Stat, Status } from '../components/ui';

interface ScStats {
  hits?: number;
  misses?: number;
  entries?: number;
  estimatedTokensSaved?: number;
  hitRate?: number;
  defaultThreshold?: number;
  note?: string;
}

export default function SemanticCachePage() {
  const { data, error, loading } = useApi<ScStats>('/api/semantic-cache');
  const hits = data?.hits ?? 0;
  const misses = data?.misses ?? 0;
  const pieData = [
    { name: 'Hits', value: hits },
    { name: 'Misses', value: misses },
  ];

  return (
    <div className="space-y-4">
      <Card title="Semantic Cache">
        <Status loading={loading} error={error} />
        {data?.note && <div className="text-sm text-zinc-400">{data.note}</div>}
        {data && !data.note && (
          <div className="grid gap-4 md:grid-cols-2">
            <div className="grid grid-cols-2 gap-3">
              <Stat label="Entries" value={(data.entries ?? 0).toLocaleString()} />
              <Stat label="Hit rate" value={`${((data.hitRate ?? 0) * 100).toFixed(1)}%`} />
              <Stat
                label="Est. tokens saved"
                value={(data.estimatedTokensSaved ?? 0).toLocaleString()}
                hint="Tokens the LLM did not have to generate"
              />
              <Stat
                label="Similarity threshold"
                value={(data.defaultThreshold ?? 0).toFixed(2)}
                hint="Default; overridable per SC.GET"
              />
            </div>
            <div>
              {hits + misses === 0 ? (
                <div className="flex h-full items-center justify-center text-sm text-zinc-500">
                  No lookups yet.
                </div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie data={pieData} dataKey="value" nameKey="name" innerRadius={55} outerRadius={85} paddingAngle={2}>
                      <Cell fill="#34d399" />
                      <Cell fill="#f87171" />
                    </Pie>
                    <Tooltip
                      contentStyle={{ background: '#18181b', border: '1px solid #3f3f46', borderRadius: 8 }}
                    />
                  </PieChart>
                </ResponsiveContainer>
              )}
              <div className="mt-2 flex justify-center gap-4 text-xs text-zinc-400">
                <span><span className="mr-1 inline-block h-2 w-2 rounded-full bg-emerald-400" />Hits: {hits.toLocaleString()}</span>
                <span><span className="mr-1 inline-block h-2 w-2 rounded-full bg-red-400" />Misses: {misses.toLocaleString()}</span>
              </div>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}
