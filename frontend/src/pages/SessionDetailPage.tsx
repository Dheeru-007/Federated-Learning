import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Activity,
  ArrowLeft,
  LayoutDashboard,
  LogOut,
  Plus,
  Shield,
  User as UserIcon,
} from 'lucide-react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import { useAuth } from '../context/AuthContext';
import { getSession, getRounds } from '../services/api';
import { connectToSession } from '../services/websocket';
import type { TrainingSession, RoundMetric, RoundUpdateMessage } from '../types';

const SessionDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const sessionId = Number(id);
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [session, setSession] = useState<TrainingSession | null>(null);
  const [rounds, setRounds] = useState<RoundMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const disconnectRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    const load = async () => {
      try {
        const [s, r] = await Promise.all([
          getSession(sessionId),
          getRounds(sessionId),
        ]);
        setSession(s);
        setRounds(r);
      } catch {
        setError('Failed to load session.');
      } finally {
        setLoading(false);
      }
    };
    void load();
  }, [sessionId]);

  useEffect(() => {
    if (!session) return;
    if (session.status !== 'RUNNING') return;

    const disconnect = connectToSession(
      sessionId,
      (msg: RoundUpdateMessage) => {
        setRounds((prev) => {
          const exists = prev.find((r) => r.roundNumber === msg.roundNumber);
          if (exists) return prev;
          return [
            ...prev,
            {
              id: msg.roundNumber,
              sessionId: msg.sessionId,
              roundNumber: msg.roundNumber,
              globalAccuracy: msg.globalAccuracy,
              globalLoss: msg.globalLoss,
              numClients: msg.numClients,
              bytesTransferred: msg.bytesTransferred,
              epsilonConsumed: msg.epsilonConsumed,
              timestamp: new Date().toISOString(),
            },
          ];
        });
      },
      (msg: RoundUpdateMessage) => {
        if (msg.status === 'COMPLETED' || msg.status === 'FAILED') {
          setSession((prev) =>
            prev ? { ...prev, status: msg.status } : prev
          );
          disconnectRef.current?.();
        }
      }
    );

    disconnectRef.current = disconnect;
    return () => disconnect();
 }, [session, sessionId]);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const statusBadgeClasses = (status: string) => {
    switch (status) {
      case 'PENDING':
        return 'bg-amber-500/10 text-amber-300 border-amber-500/40';
      case 'RUNNING':
        return 'bg-sky-500/10 text-sky-300 border-sky-500/40 animate-pulse';
      case 'COMPLETED':
        return 'bg-emerald-500/10 text-emerald-300 border-emerald-500/40';
      case 'FAILED':
        return 'bg-rose-500/10 text-rose-300 border-rose-500/40';
      default:
        return 'bg-slate-700/40 text-slate-200 border-slate-500/60';
    }
  };

  const chartData = rounds.map((r) => ({
    round: r.roundNumber,
    accuracy: parseFloat((r.globalAccuracy * 100).toFixed(2)),
    loss: parseFloat(r.globalLoss.toFixed(4)),
  }));

  const sidebar = (
    <aside className="hidden md:flex md:flex-col w-64 border-r border-slate-800 bg-slate-950/80">
      <div className="flex items-center gap-3 px-6 py-5 border-b border-slate-800">
        <div className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-500/10 border border-emerald-500/50">
          <Shield className="h-5 w-5 text-emerald-400" />
        </div>
        <div>
          <div className="text-lg font-semibold tracking-tight">FedLearn</div>
          <div className="text-[11px] uppercase tracking-[0.18em] text-slate-500">
            Federated ML Platform
          </div>
        </div>
      </div>
      <nav className="flex-1 px-4 py-4 space-y-1">
        <button
          className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-slate-200 hover:bg-slate-900/80"
          onClick={() => navigate('/dashboard')}
        >
          <LayoutDashboard className="h-4 w-4 text-emerald-400" />
          <span>Dashboard</span>
        </button>
        <button
          className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-slate-200 hover:bg-slate-900/80"
          onClick={() => navigate('/sessions/new')}
        >
          <Plus className="h-4 w-4 text-emerald-300" />
          <span>New Session</span>
        </button>
      </nav>
      <div className="mt-auto border-t border-slate-800 px-4 py-4 space-y-3">
        {user && (
          <div className="flex items-center gap-3 text-sm text-slate-300">
            <div className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-slate-900 border border-slate-700">
              <UserIcon className="h-4 w-4 text-slate-300" />
            </div>
            <div>
              <div className="font-medium truncate">{user.username}</div>
              <div className="text-[11px] uppercase tracking-[0.16em] text-slate-500">
                {user.role}
              </div>
            </div>
          </div>
        )}
        <button
          onClick={handleLogout}
          className="inline-flex w-full items-center justify-center gap-2 rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-xs font-medium text-slate-200 hover:bg-slate-800"
        >
          <LogOut className="h-3.5 w-3.5" />
          <span>Sign out</span>
        </button>
      </div>
    </aside>
  );

  if (loading) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-50 flex">
        {sidebar}
        <main className="flex-1 flex items-center justify-center">
          <span className="inline-block h-8 w-8 animate-spin rounded-full border-2 border-slate-500 border-t-transparent" />
        </main>
      </div>
    );
  }

  if (error || !session) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-50 flex">
        {sidebar}
        <main className="flex-1 flex items-center justify-center">
          <p className="text-rose-300">{error ?? 'Session not found.'}</p>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50 flex">
      {sidebar}
      <main className="flex-1 min-w-0">
        <div className="mx-auto max-w-5xl px-4 py-8 space-y-6">
          <button
            onClick={() => navigate('/dashboard')}
            className="inline-flex items-center gap-2 text-sm text-slate-400 hover:text-slate-200"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Dashboard
          </button>

          {/* Session Info Card */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-5">
            <div className="flex items-start justify-between gap-4 flex-wrap">
              <div>
                <h1 className="text-2xl font-semibold tracking-tight">
                  {session.name}
                </h1>
                <p className="text-sm text-slate-400 mt-1">
                  Created by {session.createdBy}
                </p>
              </div>
              <span
                className={`inline-flex items-center rounded-full border px-3 py-1 text-xs font-medium ${statusBadgeClasses(session.status)}`}
              >
                {session.status === 'RUNNING' && (
                  <Activity className="h-3 w-3 mr-1.5" />
                )}
                {session.status}
              </span>
            </div>

            <div className="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-4 text-sm">
              <div>
                <p className="text-xs uppercase tracking-[0.14em] text-slate-500">
                  Hospitals
                </p>
                <p className="mt-1 font-semibold">{session.numHospitals}</p>
              </div>
              <div>
                <p className="text-xs uppercase tracking-[0.14em] text-slate-500">
                  Rounds
                </p>
                <p className="mt-1 font-semibold">
                  {rounds.length} / {session.numRounds}
                </p>
              </div>
              <div>
                <p className="text-xs uppercase tracking-[0.14em] text-slate-500">
                  Privacy Budget (ε)
                </p>
                <p className="mt-1 font-semibold">{session.privacyBudget}</p>
              </div>
              <div>
                <p className="text-xs uppercase tracking-[0.14em] text-slate-500">
                  Final Accuracy
                </p>
                <p className="mt-1 font-semibold text-emerald-400">
                  {session.finalAccuracy != null
                    ? `${(session.finalAccuracy * 100).toFixed(1)}%`
                    : rounds.length > 0
                    ? `${(rounds[rounds.length - 1].globalAccuracy * 100).toFixed(1)}%`
                    : '—'}
                </p>
              </div>
            </div>
          </div>

          {/* Chart */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-5">
            <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400 mb-4">
              Training Progress
            </h2>
            {chartData.length === 0 ? (
              <div className="flex items-center justify-center h-48 text-slate-500 text-sm">
                Waiting for training rounds...
              </div>
            ) : (
              <ResponsiveContainer width="100%" height={280}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" />
                  <XAxis
                    dataKey="round"
                    stroke="#475569"
                    tick={{ fill: '#94a3b8', fontSize: 12 }}
                    label={{
                      value: 'Round',
                      position: 'insideBottom',
                      offset: -2,
                      fill: '#64748b',
                      fontSize: 11,
                    }}
                  />
                  <YAxis
                    yAxisId="acc"
                    stroke="#475569"
                    tick={{ fill: '#94a3b8', fontSize: 12 }}
                    domain={[0, 100]}
                    tickFormatter={(v) => `${v}%`}
                  />
                  <YAxis
                    yAxisId="loss"
                    orientation="right"
                    stroke="#475569"
                    tick={{ fill: '#94a3b8', fontSize: 12 }}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: '#0f172a',
                      border: '1px solid #1e293b',
                      borderRadius: 8,
                      color: '#f1f5f9',
                    }}
                  />
                  <Legend
                    wrapperStyle={{ color: '#94a3b8', fontSize: 12 }}
                  />
                  <Line
                    yAxisId="acc"
                    type="monotone"
                    dataKey="accuracy"
                    stroke="#34d399"
                    strokeWidth={2}
                    dot={false}
                    name="Accuracy (%)"
                  />
                  <Line
                    yAxisId="loss"
                    type="monotone"
                    dataKey="loss"
                    stroke="#f87171"
                    strokeWidth={2}
                    dot={false}
                    name="Loss"
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>

          {/* Rounds Table */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-800">
              <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400">
                Round Metrics
              </h2>
            </div>
            {rounds.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-slate-500">
                No rounds completed yet.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead className="bg-slate-900/80 border-b border-slate-800">
                    <tr className="text-left text-xs font-medium uppercase tracking-[0.14em] text-slate-500">
                      <th className="px-4 py-3">Round</th>
                      <th className="px-4 py-3">Accuracy</th>
                      <th className="px-4 py-3">Loss</th>
                      <th className="px-4 py-3">Clients</th>
                      <th className="px-4 py-3">ε Consumed</th>
                      <th className="px-4 py-3">Timestamp</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/80">
                    {rounds.map((r) => (
                      <tr key={r.id} className="hover:bg-slate-900/70">
                        <td className="px-4 py-3 font-medium">
                          {r.roundNumber}
                        </td>
                        <td className="px-4 py-3 text-emerald-400">
                          {(r.globalAccuracy * 100).toFixed(2)}%
                        </td>
                        <td className="px-4 py-3 text-rose-300">
                          {r.globalLoss.toFixed(4)}
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {r.numClients}
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {r.epsilonConsumed.toFixed(4)}
                        </td>
                        <td className="px-4 py-3 text-slate-400">
                          {new Date(r.timestamp).toLocaleTimeString()}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  );
};

export default SessionDetailPage;