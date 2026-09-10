import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { UserPlus } from 'lucide-react';
import * as api from '../services/api';

const RegisterPage: React.FC = () => {
  const navigate = useNavigate();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('ADMIN');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setLoading(true);
    try {
      await api.register(username.trim(), password, role);
      setSuccess('Account created successfully! Redirecting to login...');
      setTimeout(() => {
        navigate('/login', { replace: true });
      }, 1500);
    } catch (err: any) {
      if (err.response?.data?.message) {
        setError(err.response.data.message);
      } else {
        setError('Failed to register. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center px-4">
      <div className="max-w-md w-full space-y-8">
        <div className="text-center space-y-4">
          <div className="mx-auto inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-slate-900/80 border border-slate-800 shadow-lg shadow-slate-900/40">
            <UserPlus className="h-7 w-7 text-emerald-400" />
          </div>
          <div>
            <h1 className="text-3xl font-semibold tracking-tight text-slate-50">
              Create an Account
            </h1>
            <p className="mt-2 text-sm text-slate-400">
              Join the Federated Learning Platform
            </p>
          </div>
        </div>

        <form
          onSubmit={handleSubmit}
          className="mt-6 space-y-6 rounded-2xl border border-slate-800 bg-slate-900/60 px-6 py-6 shadow-xl shadow-slate-950/60 backdrop-blur"
        >
          <div className="space-y-2">
            <label htmlFor="username" className="block text-sm font-medium text-slate-200">
              Username
            </label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 shadow-sm outline-none ring-0 transition focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400 placeholder:text-slate-500"
              placeholder="Choose a username"
              required
            />
          </div>

          <div className="space-y-2">
            <label htmlFor="password" className="block text-sm font-medium text-slate-200">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 shadow-sm outline-none ring-0 transition focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400 placeholder:text-slate-500"
              placeholder="Create a password"
              required
            />
          </div>

          <div className="space-y-2">
            <label htmlFor="role" className="block text-sm font-medium text-slate-200">
              Role
            </label>
            <select
              id="role"
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="block w-full rounded-lg border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-50 shadow-sm outline-none ring-0 transition focus:border-emerald-400 focus:ring-1 focus:ring-emerald-400"
            >
              <option value="ADMIN">Admin</option>
              <option value="VIEWER">Viewer</option>
            </select>
          </div>

          {error && (
            <div className="rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
              {error}
            </div>
          )}
          
          {success && (
            <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200">
              {success}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="inline-flex w-full items-center justify-center rounded-lg bg-emerald-500 px-4 py-2.5 text-sm font-medium text-emerald-950 shadow-lg shadow-emerald-500/30 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-70"
          >
            {loading ? (
              <>
                <span className="mr-2 inline-block h-4 w-4 animate-spin rounded-full border-2 border-emerald-950 border-t-transparent" />
                Creating account...
              </>
            ) : (
              'Sign up'
            )}
          </button>
          
          <div className="mt-4 text-center">
            <p className="text-sm text-slate-400">
              Already have an account?{' '}
              <Link to="/login" className="font-medium text-emerald-400 hover:text-emerald-300 transition-colors">
                Sign in
              </Link>
            </p>
          </div>
        </form>

        <p className="text-center text-xs text-slate-500">
          By signing up you acknowledge that this environment is for{' '}
          <span className="font-medium text-slate-300">
            federated learning experiments only
          </span>
          . No real patient data is used.
        </p>
      </div>
    </div>
  );
};

export default RegisterPage;
