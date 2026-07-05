import { useApi } from '../useApi';
import { Card, Chip, Status } from '../components/ui';

interface RateLimitInfo {
  enabled: boolean;
  note: string;
}

export default function RateLimitersPage() {
  const { data, error, loading } = useApi<RateLimitInfo>('/api/ratelimit');
  return (
    <Card title="Rate Limiters">
      <Status loading={loading} error={error} />
      {data && (
        <div className="space-y-3">
          <div>
            Subsystem:{' '}
            {data.enabled ? <Chip tone="ok">enabled</Chip> : <Chip tone="off">disabled</Chip>}
          </div>
          <p className="max-w-xl text-sm text-zinc-400">{data.note}</p>
          <p className="max-w-xl text-xs text-zinc-500">
            Limiter state lives in rotating per-window CRDT buckets, so there is no cheap
            server-side enumeration today. Per-limiter live usage will land alongside
            metric publication (Phase 13).
          </p>
        </div>
      )}
    </Card>
  );
}
