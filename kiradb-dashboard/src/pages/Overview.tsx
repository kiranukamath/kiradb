import { useApi } from '../useApi';
import { Card, Chip, Stat, Status } from '../components/ui';

interface Overview {
  nodeId: string;
  uptimeSeconds: number;
  version: string;
  port: number;
  role: string;
}

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  return `${m}m ${s}s`;
}

export default function OverviewPage() {
  const { data, error, loading } = useApi<Overview>('/api/overview');
  return (
    <Card title="Node Overview">
      <Status loading={loading} error={error} />
      {data && (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat label="Node ID" value={data.nodeId} />
          <Stat label="Uptime" value={formatUptime(data.uptimeSeconds)} />
          <Stat label="Version" value={data.version} />
          <Stat
            label="Role"
            value={<Chip tone={data.role === 'standalone' ? 'off' : 'ok'}>{data.role}</Chip>}
            hint={`RESP3 on port ${data.port}`}
          />
        </div>
      )}
    </Card>
  );
}
