import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  LayoutDashboard,
  LogOut,
  Plus,
  Shield,
  User as UserIcon,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { createSession } from '../services/api';
import type { NewSessionConfig } from '../services/api';
export {};

const NewSessionPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState<NewSessionConfig>({
    sessionName: '',
    numHospitals: 5,
    numRounds: 20,
    privacyBudget: 0.5,
    clipNorm: 1.0,
    noiseSigma: 0.05,
    localEpochs: 7,
  });

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const privacyLabel = () => {
    if (form.privacyBudget < 0.5)
      return { text: 'Strong Privacy', color: 'text-emerald-400' };
    if (form.privacyBudget < 1.0)
      return { text: 'Moderate Privacy', color: 'text-amber-400' };
    return { text: 'Low Privacy', color: 'text-rose-400' };
  };

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement>
  ) => {
    const { name, value, type } = e.target;
    setForm((prev) => ({
      ...prev,
      [name]: type === 'number' || type === 'range'
        ? parseFloat(value)
        : value,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const session = await createSession(form);
      navigate(`/sessions/${session.id}`);
    } catch {
      setError('Failed to create session. Is the backend running?');
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const privacy = privacyLabel();

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50 flex">
      {/* Sidebar */}
      <aside className="hidden md:flex md:flex-col w-64 border-r border-slate-800 bg-slate-950/80">
        <div className="flex items-center gap-3 px-6 py-5 border-b border-slate-800">
          <div className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-500/10 border border-emerald-500/50">
            <Shield className="h-5 w-5 text-emerald-400" />
          </div>
          <div>
            <div className="text-lg font-semibold tracking-tight">
              FedLearn
            </div>
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
            className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium bg-slate-900 text-slate-50"
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

      {/* Main */}
      <main className="flex-1 min-w-0">
        <div className="mx-auto max-w-2xl px-4 py-8">
          <button
            onClick={() => navigate('/dashboard')}
            className="inline-flex items-center gap-2 text-sm text-slate-400 hover:text-slate-200 mb-6"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Dashboard
          </button>

          <h1 className="text-2xl font-semibold tracking-tight mb-1">
            New Training Session
          </h1>
          <p className="text-sm text-slate-400 mb-8">
            Configure and launch a federated learning experiment
            across hospital nodes.
          </p>

          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Session Name */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-5 space-y-4">
              <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400">
                General
              </h2>
              <div className="space-y-2">
                <label className="block text-sm font-medium text-slate-200">
                  Session Name
                </label>
                <input
                  type="text"
                  name="sessionName"
                  value={form.sessionName}
                  onChange={handleChange}
                  required
                  placeholder="e.g. Hospital Network — Q1 2025"
                  className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400 placeholder:text-slate-500"
                />
              </div>
            </div>

            {/* FL Config */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-5 space-y-4">
              <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400">
                Federated Learning Config
              </h2>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-slate-200">
                    Number of Hospitals
                  </label>
                  <input
                    type="number"
                    name="numHospitals"
                    value={form.numHospitals}
                    onChange={handleChange}
                    min={2}
                    max={9}
                    className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
                  />
                </div>
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-slate-200">
                    Training Rounds
                  </label>
                  <input
                    type="number"
                    name="numRounds"
                    value={form.numRounds}
                    onChange={handleChange}
                    min={5}
                    max={50}
                    className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
                  />
                </div>
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-slate-200">
                    Local Epochs
                  </label>
                  <input
                    type="number"
                    name="localEpochs"
                    value={form.localEpochs}
                    onChange={handleChange}
                    min={1}
                    max={20}
                    className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
                  />
                </div>
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-slate-200">
                    Clip Norm
                  </label>
                  <input
                    type="number"
                    name="clipNorm"
                    value={form.clipNorm}
                    onChange={handleChange}
                    step={0.1}
                    min={0.1}
                    className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
                  />
                </div>
              </div>
            </div>

            {/* Privacy Config */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-5 space-y-4">
              <h2 className="text-sm font-semibold uppercase tracking-[0.16em] text-slate-400">
                Privacy Configuration
              </h2>
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium text-slate-200">
                    Privacy Budget (ε)
                  </label>
                  <div className="flex items-center gap-2">
                    <span className="text-lg font-semibold text-slate-50">
                      {form.privacyBudget.toFixed(1)}
                    </span>
                    <span className={`text-xs font-medium ${privacy.color}`}>
                      {privacy.text}
                    </span>
                  </div>
                </div>
                <input
                  type="range"
                  name="privacyBudget"
                  value={form.privacyBudget}
                  onChange={handleChange}
                  min={0.1}
                  max={2.0}
                  step={0.1}
                  className="w-full accent-emerald-400"
                />
                <div className="flex justify-between text-xs text-slate-500">
                  <span>0.1 (Max Privacy)</span>
                  <span>2.0 (Max Utility)</span>
                </div>
              </div>
              <div className="space-y-2">
                <label className="block text-sm font-medium text-slate-200">
                  Noise Sigma (σ)
                </label>
                <input
                  type="number"
                  name="noiseSigma"
                  value={form.noiseSigma}
                  onChange={handleChange}
                  step={0.01}
                  min={0.01}
                  className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 outline-none focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
                />
              </div>
            </div>

            {error && (
              <div className="rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
                {error}
              </div>
            )}

            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => navigate('/dashboard')}
                className="flex-1 rounded-lg border border-slate-700 bg-slate-900 px-4 py-2.5 text-sm font-medium text-slate-200 hover:bg-slate-800"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={loading}
                className="flex-1 inline-flex items-center justify-center gap-2 rounded-lg bg-emerald-500 px-4 py-2.5 text-sm font-medium text-emerald-950 shadow-lg shadow-emerald-500/30 hover:bg-emerald-400 disabled:opacity-70 disabled:cursor-not-allowed"
              >
                {loading ? (
                  <>
                    <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-emerald-950 border-t-transparent" />
                    Starting...
                  </>
                ) : (
                  <>
                    <Plus className="h-4 w-4" />
                    Start Training
                  </>
                )}
              </button>
            </div>
          </form>
        </div>
      </main>
    </div>
  );
};

export default NewSessionPage;