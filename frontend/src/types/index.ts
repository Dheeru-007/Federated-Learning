export interface TrainingSession {
  id: number;
  name: string;
  createdBy: string;
  status: string; // PENDING, RUNNING, COMPLETED, FAILED
  numHospitals: number;
  numRounds: number;
  privacyBudget: number;
  startedAt: string | null;
  finishedAt: string | null;
  finalAccuracy: number | null;
  finalLoss: number | null;
}

export interface RoundMetric {
  id: number;
  sessionId: number;
  roundNumber: number;
  globalAccuracy: number;
  globalLoss: number;
  numClients: number;
  bytesTransferred: number;
  epsilonConsumed: number;
  timestamp: string;
}

export interface ClientMetric {
  id: number;
  sessionId: number;
  roundNumber: number;
  clientId: number;
  hospitalName: string;
  localAccuracy: number;
  epsilonConsumed: number;
  bytesSent: number;
  timestamp: string;
}

export interface RoundUpdateMessage {
  sessionId: number;
  roundNumber: number;
  totalRounds: number;
  globalAccuracy: number;
  globalLoss: number;
  epsilonConsumed: number;
  bytesTransferred: number;
  numClients: number;
  status: string;
  message: string;
}

export type UserRole = 'ADMIN' | 'VIEWER';

export interface User {
  username: string;
  role: UserRole;
  token: string;
}

