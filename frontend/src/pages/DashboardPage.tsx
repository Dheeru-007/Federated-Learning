import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  Database,
  LayoutDashboard,
  LogOut,
  Plus,
  Shield,
  Target,
  User as UserIcon,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import type { TrainingSession } from '../types';
import { getSessions } from '../services/api';

const DashboardPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [sessions, setSessions] = useState<TrainingSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSessions = useCallback(async () => {
    try {
      setError(null);
      const data = await getSessions();
      setSessions(data);
    } catch {
      setError('Failed to load sessions from backend.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    if (!sessions.some((s) => s.status === 'RUNNING')) {
      return;
    }

    const id = window.setInterval(() => {
      void loadSessions();
    }, 5000);
    return () => window.clearInterval(id);
  }, [sessions, loadSessions]);

  const totalSessions = sessions.length;
  const runningSessions = sessions.filter((s) => s.status === 'RUNNING').length;

  const bestAccuracyValue =
    sessions
      .filter((s) => s.finalAccuracy != null)
      .reduce<number>(
        (max, s) => (s.finalAccuracy! > max ? s.finalAccuracy! : max),
        0
      ) ?? 0;

  const avgPrivacyBudget =
    sessions.length > 0
      ? sessions.reduce((sum, s) => sum + s.privacyBudget, 0) / sessions.length
      : 0;

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const handleNewSession = () => {
    navigate('/sessions/new');
  };

  const handleViewSession = (id: number) => {
    navigate(`/sessions/${id}`);
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

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50 flex">
      {/* Sidebar */}
      <aside className="hidden md:flex md:flex-col w-64 border-r border-slate-800 bg-slate-950/80 backdrop-blur-md">
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
            className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium bg-slate-900 text-slate-50 hover:bg-slate-900/80"
            onClick={() => navigate('/dashboard')}
          >
            <LayoutDashboard className="h-4 w-4 text-emerald-400" />
            <span>Dashboard</span>
          </button>
          <button
            className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-slate-200 hover:bg-slate-900/80"
            onClick={handleNewSession}
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

      {/* Main content */}
      <main className="flex-1 min-w-0 md:ml-0">
        <div className="mx-auto max-w-6xl px-4 py-6 md:py-8">
          {/* Top header */}
          <div className="flex items-center justify-between gap-3 mb-6">
            <div>
              <h1 className="text-2xl md:text-3xl font-semibold tracking-tight">
                Network Overview
              </h1>
              <p className="mt-1 text-sm text-slate-400">
                Monitor federated training sessions across all hospital nodes.
              </p>
            </div>
            <button
              onClick={handleNewSession}
              className="inline-flex items-center gap-2 rounded-lg bg-emerald-500 px-4 py-2 text-sm font-medium text-emerald-950 shadow-lg shadow-emerald-500/30 hover:bg-emerald-400"
            >
              <Plus className="h-4 w-4" />
              <span>New Session</span>
            </button>
          </div>

          {/* Stats */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 shadow shadow-slate-950/40">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-[0.16em] text-slate-500">
                    Total Sessions
                  </p>
                  <p className="mt-2 text-2xl font-semibold">{totalSessions}</p>
                </div>
                <div className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-slate-900 border border-slate-700">
                  <Database className="h-4 w-4 text-emerald-400" />
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 shadow shadow-slate-950/40">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-[0.16em] text-slate-500">
                    Running Sessions
                  </p>
                  <p className="mt-2 text-2xl font-semibold">{runningSessions}</p>
                </div>
                <div className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-slate-900 border border-slate-700">
                  <Activity className="h-4 w-4 text-sky-400" />
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 shadow shadow-slate-950/40">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-[0.16em] text-slate-500">
                    Best Accuracy
                  </p>
                  <p className="mt-2 text-2xl font-semibold">
                    {(bestAccuracyValue * 100).toFixed(1)}%
                  </p>
                </div>
                <div className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-slate-900 border border-slate-700">
                  <Target className="h-4 w-4 text-emerald-400" />
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 shadow shadow-slate-950/40">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-[0.16em] text-slate-500">
                    Avg Privacy Budget
                  </p>
                  <p className="mt-2 text-2xl font-semibold">
                    {avgPrivacyBudget.toFixed(2)}
                  </p>
                </div>
                <div className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-slate-900 border border-slate-700">
                  <Shield className="h-4 w-4 text-emerald-400" />
                </div>
              </div>
            </div>
          </div>

          {/* Sessions table / states */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 shadow-lg shadow-slate-950/50 overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800">
              <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400">
                Training Sessions
              </h2>
            </div>

            {loading ? (
              <div className="flex items-center justify-center py-12">
                <span className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-slate-500 border-t-transparent" />
                <span className="ml-3 text-sm text-slate-400">Loading sessions…</span>
              </div>
            ) : error ? (
              <div className="px-4 py-6 text-sm text-rose-300">
                {error}
              </div>
            ) : sessions.length === 0 ? (
              <div className="px-6 py-10 text-center text-sm text-slate-400">
                <p className="mb-3 font-medium text-slate-200">
                  No training sessions yet.
                </p>
                <p className="mb-6">
                  Start a new federated experiment to see convergence, accuracy, and privacy metrics in real time.
                </p>
                <button
                  onClick={handleNewSession}
                  className="inline-flex items-center gap-2 rounded-lg bg-emerald-500 px-4 py-2 text-sm font-medium text-emerald-950 shadow shadow-emerald-500/30 hover:bg-emerald-400"
                >
                  <Plus className="h-4 w-4" />
                  <span>Start first session</span>
                </button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead className="bg-slate-900/80 border-b border-slate-800">
                    <tr className="text-left text-xs font-medium uppercase tracking-[0.14em] text-slate-500">
                      <th className="px-4 py-3">Name</th>
                      <th className="px-4 py-3">Status</th>
                      <th className="px-4 py-3">Hospitals</th>
                      <th className="px-4 py-3">Rounds</th>
                      <th className="px-4 py-3">Accuracy</th>
                      <th className="px-4 py-3">Privacy Budget</th>
                      <th className="px-4 py-3">Started</th>
                      <th className="px-4 py-3 text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/80">
                    {sessions.map((session) => (
                      <tr key={session.id} className="hover:bg-slate-900/70">
                        <td className="px-4 py-3">
                          <div className="font-medium text-slate-50">
                            {session.name}
                          </div>
                          <div className="text-xs text-slate-500">
                            created by {session.createdBy}
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${statusBadgeClasses(
                              session.status
                            )}`}
                          >
                            {session.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {session.numHospitals}
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {session.numRounds}
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {session.finalAccuracy != null
                            ? `${(session.finalAccuracy * 100).toFixed(1)}%`
                            : '—'}
                        </td>
                        <td className="px-4 py-3 text-slate-200">
                          {session.privacyBudget.toFixed(2)}
                        </td>
                        <td className="px-4 py-3 text-slate-300">
                          {session.startedAt
                            ? new Date(session.startedAt).toLocaleString()
                            : '—'}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button
                            onClick={() => handleViewSession(session.id)}
                            className="inline-flex items-center rounded-lg border border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-100 hover:bg-slate-800"
                          >
                            View
                          </button>
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

export default DashboardPage;

