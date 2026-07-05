import { Card, Chip } from '../components/ui';

/**
 * Honest placeholder: kiradb-raft exists as a module, but the server bootstrap
 * runs single-node today — Raft is not wired into KiraDBServer.main() yet.
 * When it is, /api/overview will report a real role and this page will show
 * term, log index, and election history.
 */
export default function RaftPage() {
  return (
    <Card title="Raft Consensus">
      <div className="space-y-3">
        <Chip tone="off">standalone — Raft not wired into this node</Chip>
        <p className="max-w-xl text-sm text-zinc-400">
          The Raft implementation (leader election, log replication, chaos-tested failover)
          lives in the <span className="font-mono text-zinc-300">kiradb-raft</span> module but is
          not yet connected to the server bootstrap. Once clustering ships, this page will show
          the current term, commit index, leader identity, and an election-history timeline.
        </p>
      </div>
    </Card>
  );
}
