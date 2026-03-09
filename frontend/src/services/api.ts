import axios from 'axios';
import type {
  TrainingSession,
  RoundMetric,
  ClientMetric,
  User,
} from '../types';

const API_BASE_URL = 'http://localhost:8080';

export interface NewSessionConfig {
  sessionName: string;
  numHospitals: number;
  numRounds: number;
  privacyBudget: number;
  clipNorm: number;
  noiseSigma: number;
  localEpochs: number;
}

type LoginResponse = User;

interface BackendRoundMetric {
  id: number;
  session?: { id: number } | null;
  roundNumber: number;
  globalAccuracy: number;
  globalLoss: number;
  numClients: number;
  bytesTransferred: number;
  epsilonConsumed: number;
  timestamp: string;
}

interface BackendClientMetric {
  id: number;
  session?: { id: number } | null;
  roundNumber: number;
  clientId: number;
  hospitalName: string;
  localAccuracy: number;
  epsilonConsumed: number;
  bytesSent: number;
  timestamp: string;
}

const api = axios.create({
  baseURL: API_BASE_URL,
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});


export async function login(username: string, password: string): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/api/auth/login', { username, password });
  return data;
}

export async function register(username: string, password: string, role: string): Promise<{ message: string; username: string }> {
  const { data } = await api.post<{ message: string; username: string }>('/api/auth/register', {
    username,
    password,
    role,
  });
  return data;
}

export async function getSessions(): Promise<TrainingSession[]> {
  const { data } = await api.get<TrainingSession[]>('/api/sessions');
  return data;
}

export async function getSession(id: number): Promise<TrainingSession> {
  const { data } = await api.get<TrainingSession>(`/api/sessions/${id}`);
  return data;
}

export async function createSession(config: NewSessionConfig): Promise<TrainingSession> {
  const { data } = await api.post<TrainingSession>('/api/sessions', config);
  return data;
}

export async function getRounds(sessionId: number): Promise<RoundMetric[]> {
  const { data } = await api.get<BackendRoundMetric[]>(`/api/sessions/${sessionId}/rounds`);
  return data.map((r) => ({
    id: r.id,
    sessionId: r.session?.id ?? sessionId,
    roundNumber: r.roundNumber,
    globalAccuracy: r.globalAccuracy,
    globalLoss: r.globalLoss,
    numClients: r.numClients,
    bytesTransferred: r.bytesTransferred,
    epsilonConsumed: r.epsilonConsumed,
    timestamp: r.timestamp,
  }));
}

export async function getClients(sessionId: number): Promise<ClientMetric[]> {
  const { data } = await api.get<BackendClientMetric[]>(`/api/sessions/${sessionId}/clients`);
  return data.map((c) => ({
    id: c.id,
    sessionId: c.session?.id ?? sessionId,
    roundNumber: c.roundNumber,
    clientId: c.clientId,
    hospitalName: c.hospitalName,
    localAccuracy: c.localAccuracy,
    epsilonConsumed: c.epsilonConsumed,
    bytesSent: c.bytesSent,
    timestamp: c.timestamp,
  }));
}

