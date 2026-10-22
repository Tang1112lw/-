import type { AppStatus, SessionStatus } from './types';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await response.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  if (!response.ok) {
    const message = (data as { message?: string })?.message || `HTTP ${response.status}`;
    throw new Error(message);
  }
  return data as T;
}

export const api = {
  status: () => request<AppStatus>('/api/status'),
  sessions: () => request<SessionStatus[]>('/api/sessions'),
  start: (id: string) =>
    request<{ ok: boolean; message: string }>(`/api/sessions/${id}/start`, { method: 'POST' }),
  login: (id: string) =>
    request<{ ok: boolean; message: string }>(`/api/sessions/${id}/login`, { method: 'POST' }),
  startAll: () =>
    request<{ ok: boolean; message: string }>('/api/sessions/start-all', { method: 'POST' }),
  stopAll: () =>
    request<{ ok: boolean; message: string }>('/api/sessions/stop-all', { method: 'POST' }),
  saveInterval: (minSeconds: number, maxSeconds: number) =>
    request<{ ok: boolean; minSeconds: number; maxSeconds: number }>('/api/settings/interval', {
      method: 'POST',
      body: JSON.stringify({ minSeconds, maxSeconds }),
    }),
  updateRoom: (id: string, roomUrl: string) =>
    request<{ ok: boolean; message: string; roomUrl: string }>(`/api/sessions/${id}/room`, {
      method: 'POST',
      body: JSON.stringify({ roomUrl }),
    }),
};