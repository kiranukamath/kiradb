import { useState } from 'react';
import OverviewPage from './pages/Overview';
import StoragePage from './pages/Storage';
import CommandsPage from './pages/Commands';
import FlagsPage from './pages/Flags';
import RateLimitersPage from './pages/RateLimiters';
import ConfigPage from './pages/Config';
import SemanticCachePage from './pages/SemanticCache';
import RaftPage from './pages/Raft';

const TABS = [
  { id: 'overview', label: 'Overview', page: <OverviewPage /> },
  { id: 'storage', label: 'Storage', page: <StoragePage /> },
  { id: 'commands', label: 'Commands', page: <CommandsPage /> },
  { id: 'flags', label: 'Feature Flags', page: <FlagsPage /> },
  { id: 'ratelimit', label: 'Rate Limiters', page: <RateLimitersPage /> },
  { id: 'config', label: 'Config', page: <ConfigPage /> },
  { id: 'semantic-cache', label: 'Semantic Cache', page: <SemanticCachePage /> },
  { id: 'raft', label: 'Raft', page: <RaftPage /> },
] as const;

export default function App() {
  const [active, setActive] = useState<string>('overview');
  const current = TABS.find((t) => t.id === active) ?? TABS[0];

  return (
    <div className="min-h-screen">
      <header className="border-b border-zinc-800 bg-zinc-900/80 px-6 py-4">
        <div className="mx-auto flex max-w-6xl items-center gap-3">
          <span className="text-xl font-bold tracking-tight text-zinc-50">
            Kira<span className="text-emerald-400">DB</span>
          </span>
          <span className="text-xs text-zinc-500">dashboard · polls every 2s</span>
        </div>
      </header>

      <nav className="border-b border-zinc-800 bg-zinc-900/40 px-6">
        <div className="mx-auto flex max-w-6xl gap-1 overflow-x-auto">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActive(tab.id)}
              className={`whitespace-nowrap border-b-2 px-3 py-2.5 text-sm transition-colors ${
                tab.id === active
                  ? 'border-emerald-400 font-medium text-zinc-50'
                  : 'border-transparent text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </nav>

      <main className="mx-auto max-w-6xl px-6 py-6">{current.page}</main>
    </div>
  );
}
