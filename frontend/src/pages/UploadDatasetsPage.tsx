import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  CheckCircle,
  Upload,
  Shield,
  AlertTriangle,
  FileText,
  Loader,
  Play,
  User as UserIcon,
  LogOut,
  LayoutDashboard,
  Plus,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import {
  getSession,
  getUploadStatus,
  uploadDataset,
  startTraining,
} from '../services/api';
import type { UploadStatus } from '../services/api';
export {};

interface HospitalUploadState {
  status: 'idle' | 'uploading' | 'done' | 'error';
  fileName?: string;
  rows?: number;
  features?: number;
  error?: string;
}

const UploadDatasetsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const sessionId = parseInt(id ?? '0');
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [sessionName, setSessionName] = useState('');
  const [numHospitals, setNumHospitals] = useState(0);
  const [uploadStatus, setUploadStatus] = useState<UploadStatus | null>(null);
  const [hospitalStates, setHospitalStates] = useState<HospitalUploadState[]>([]);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const fileInputRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    const init = async () => {
      try {
        const session = await getSession(sessionId);
        setSessionName(session.name);
        setNumHospitals(session.numHospitals);
        setHospitalStates(Array(session.numHospitals).fill({ status: 'idle' }));
        fileInputRefs.current = Array(session.numHospitals).fill(null);

        const status = await getUploadStatus(sessionId);
        setUploadStatus(status);

        // Mark already uploaded hospitals
        const states: HospitalUploadState[] = Array(session.numHospitals)
          .fill(null)
          .map((_, i) => {
            const existing = status.datasets.find((d) => d.hospitalId === i);
            if (existing) {
              return {
                status: 'done',
                fileName: `hospital-${i}.csv`,
                rows: existing.rows,
                features: existing.features,
              };
            }
            return { status: 'idle' };
          });
        setHospitalStates(states);
      } catch {
        navigate('/dashboard');
      }
    };
    init();
  }, [sessionId, navigate]);

  const handleFileChange = async (
    e: React.ChangeEvent<HTMLInputElement>,
    hospitalId: number
  ) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setHospitalStates((prev) => {
      const next = [...prev];
      next[hospitalId] = { status: 'uploading', fileName: file.name };
      return next;
    });

    try {
      const result = await uploadDataset(sessionId, hospitalId, file);
      setHospitalStates((prev) => {
        const next = [...prev];
        next[hospitalId] = {
          status: 'done',
          fileName: file.name,
          rows: result.rows,
          features: result.features,
        };
        return next;
      });

      // Refresh upload status
      const status = await getUploadStatus(sessionId);
      setUploadStatus(status);
    } catch (err: any) {
      const message =
        err?.response?.data?.error ?? 'Upload failed. Check CSV format.';
      setHospitalStates((prev) => {
        const next = [...prev];
        next[hospitalId] = { status: 'error', fileName: file.name, error: message };
        return next;
      });
    }

    // Reset file input
    if (fileInputRefs.current[hospitalId]) {
      fileInputRefs.current[hospitalId]!.value = '';
    }
  };

  const handleStartTraining = async () => {
    setStartError(null);
    setStarting(true);
    try {
      await startTraining(sessionId);
      navigate(`/sessions/${sessionId}`);
    } catch (err: any) {
      setStartError(err?.response?.data?.error ?? 'Failed to start training.');
    } finally {
      setStarting(false);
    }
  };

  const allUploaded = uploadStatus?.allUploaded ?? false;
  const uploadedCount = uploadStatus?.hospitalsUploaded ?? 0;

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50 flex">
      {/* Sidebar */}
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
            Upload Hospital Datasets
          </h1>
          <p className="text-sm text-slate-400 mb-2">
            Session: <span className="text-slate-200">{sessionName}</span>
          </p>
          <p className="text-sm text-slate-400 mb-8">
            Each hospital uploads their own private CSV dataset. Data never
            leaves the hospital — only model weights are shared.
          </p>

          {/* Progress */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-6 py-4 mb-6">
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium text-slate-200">
                Upload Progress
              </span>
              <span className="text-sm font-semibold text-emerald-400">
                {uploadedCount} / {numHospitals} hospitals
              </span>
            </div>
            <div className="w-full bg-slate-800 rounded-full h-2">
              <div
                className="bg-emerald-500 h-2 rounded-full transition-all duration-500"
                style={{
                  width: numHospitals > 0
                    ? `${(uploadedCount / numHospitals) * 100}%`
                    : '0%',
                }}
              />
            </div>
            {uploadStatus?.featureCount && (
              <p className="mt-2 text-xs text-slate-500">
                Feature count locked to{' '}
                <span className="text-slate-300">
                  {uploadStatus.featureCount} features
                </span>{' '}
                from first upload. All CSVs must match.
              </p>
            )}
          </div>

          {/* CSV Format Info */}
          <div className="rounded-xl border border-slate-700 bg-slate-900/40 px-5 py-4 mb-6">
            <div className="flex items-center gap-2 mb-2">
              <FileText className="h-4 w-4 text-slate-400" />
              <span className="text-sm font-medium text-slate-300">
                CSV Format Requirements
              </span>
            </div>
            <ul className="text-xs text-slate-500 space-y-1 list-disc list-inside">
              <li>First row must be a header row</li>
              <li>Last column must be the label (0 or 1)</li>
              <li>All other columns are numeric features</li>
              <li>No missing values allowed</li>
              <li>All hospitals must have the same number of features</li>
            </ul>
          </div>

          {/* Hospital Upload Cards */}
          <div className="space-y-3 mb-6">
            {Array.from({ length: numHospitals }).map((_, i) => {
              const state = hospitalStates[i] ?? { status: 'idle' };
              return (
                <div
                  key={i}
                  className={`rounded-xl border px-5 py-4 transition-all ${
                    state.status === 'done'
                      ? 'border-emerald-500/50 bg-emerald-500/5'
                      : state.status === 'error'
                      ? 'border-rose-500/50 bg-rose-500/5'
                      : 'border-slate-800 bg-slate-900/60'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div
                        className={`inline-flex h-8 w-8 items-center justify-center rounded-lg text-xs font-bold ${
                          state.status === 'done'
                            ? 'bg-emerald-500/20 text-emerald-400'
                            : state.status === 'error'
                            ? 'bg-rose-500/20 text-rose-400'
                            : 'bg-slate-800 text-slate-400'
                        }`}
                      >
                        {i}
                      </div>
                      <div>
                        <div className="text-sm font-medium text-slate-200">
                          Hospital-{i}
                        </div>
                        {state.status === 'done' && (
                          <div className="text-xs text-slate-500">
                            {state.fileName} — {state.rows?.toLocaleString()} rows,{' '}
                            {state.features} features
                          </div>
                        )}
                        {state.status === 'error' && (
                          <div className="text-xs text-rose-400">{state.error}</div>
                        )}
                        {state.status === 'idle' && (
                          <div className="text-xs text-slate-500">
                            No dataset uploaded
                          </div>
                        )}
                        {state.status === 'uploading' && (
                          <div className="text-xs text-slate-400">
                            Uploading {state.fileName}...
                          </div>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      {state.status === 'done' && (
                        <CheckCircle className="h-5 w-5 text-emerald-400" />
                      )}
                      {state.status === 'error' && (
                        <AlertTriangle className="h-5 w-5 text-rose-400" />
                      )}
                      {state.status === 'uploading' && (
                        <Loader className="h-5 w-5 text-slate-400 animate-spin" />
                      )}

                      <input
                        type="file"
                        accept=".csv"
                        className="hidden"
                        ref={(el) => { fileInputRefs.current[i] = el; }}
                        onChange={(e) => handleFileChange(e, i)}
                      />
                      <button
                        type="button"
                        disabled={state.status === 'uploading'}
                        onClick={() => fileInputRefs.current[i]?.click()}
                        className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all ${
                          state.status === 'done'
                            ? 'border border-slate-700 bg-slate-900 text-slate-300 hover:bg-slate-800'
                            : 'bg-emerald-500 text-emerald-950 hover:bg-emerald-400'
                        } disabled:opacity-50 disabled:cursor-not-allowed`}
                      >
                        <Upload className="h-3.5 w-3.5" />
                        {state.status === 'done' ? 'Replace' : 'Upload CSV'}
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {startError && (
            <div className="rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-200 mb-4">
              {startError}
            </div>
          )}

          {/* Start Training Button */}
          <button
            onClick={handleStartTraining}
            disabled={!allUploaded || starting}
            className="w-full inline-flex items-center justify-center gap-2 rounded-lg bg-emerald-500 px-4 py-3 text-sm font-medium text-emerald-950 shadow-lg shadow-emerald-500/30 hover:bg-emerald-400 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {starting ? (
              <>
                <Loader className="h-4 w-4 animate-spin" />
                Starting Training...
              </>
            ) : (
              <>
                <Play className="h-4 w-4" />
                {allUploaded
                  ? 'Start Federated Training'
                  : `Upload ${numHospitals - uploadedCount} more dataset${numHospitals - uploadedCount !== 1 ? 's' : ''} to continue`}
              </>
            )}
          </button>
        </div>
      </main>
    </div>
  );
};

export default UploadDatasetsPage;