import { useApi } from '../useApi';
import { Card, Stat, Status } from '../components/ui';

interface StorageStats {
  memCacheSize?: number;
  memCacheMaxEntries?: number;
  trackedKeys?: number;
  hotKeys?: number;
  warmTrackedKeys?: number;
  note?: string;
}

export default function StoragePage() {
  const { data, error, loading } = useApi<StorageStats>('/api/storage');
  const used = data?.memCacheSize ?? 0;
  const max = data?.memCacheMaxEntries ?? 0;
  const pct = max > 0 ? Math.min(100, (used / max) * 100) : 0;

  return (
    <div className="space-y-4">
      <Card title="MemCache (Tier 1)">
        <Status loading={loading} error={error} />
        {data?.note && <div className="text-sm text-zinc-400">{data.note}</div>}
        {data && !data.note && (
          <>
            <div className="mb-4">
              <div className="mb-1 flex justify-between text-xs text-zinc-400">
                <span>Capacity</span>
                <span>
                  {used.toLocaleString()} / {max.toLocaleString()} entries ({pct.toFixed(1)}%)
                </span>
              </div>
              <div className="h-3 overflow-hidden rounded-full bg-zinc-800">
                <div
                  className={`h-full rounded-full transition-all ${pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-emerald-500'}`}
                  style={{ width: `${pct}%` }}
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
              <Stat label="Hot keys (in MemCache)" value={data.hotKeys?.toLocaleString() ?? '—'} />
              <Stat
                label="Warm tracked keys"
                value={data.warmTrackedKeys?.toLocaleString() ?? '—'}
                hint="Tracked by AccessTracker but not hot"
              />
              <Stat
                label="Tracked keys (working set)"
                value={data.trackedKeys?.toLocaleString() ?? '—'}
                hint="Untracked cold keys live on disk only"
              />
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
