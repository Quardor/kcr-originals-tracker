import { getAccessToken } from './client';

/**
 * Клиент AI-сервиса (kcr-ml, ARCHITECTURE.md §7): те же access-токены kcr-backend,
 * контракт ошибок совпадает. Токен в WS уходит query-параметром — компромисс MVP
 * (браузер не позволяет заголовок Authorization в WebSocket).
 */
const AI_BASE_URL = import.meta.env.VITE_AI_BASE_URL ?? 'http://localhost:8000/api/v1/ai';

export class AiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'AiError';
    this.status = status;
    this.code = code;
  }
}

function aiHeaders() {
  const headers = {};
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function parseErrorResponse(response) {
  let code = null;
  let message = `HTTP ${response.status}`;
  try {
    const body = await response.json();
    if (body && typeof body.code === 'string') code = body.code;
    if (body && typeof body.message === 'string' && body.message) message = body.message;
  } catch {
    // не JSON — остаётся дефолт
  }
  return new AiError(response.status, code, message);
}

async function aiFetch(path, { method = 'GET', body } = {}) {
  const headers = aiHeaders();
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(`${AI_BASE_URL}${path}`, { method, headers, body: payload });
  } catch {
    throw new AiError(0, 'NETWORK_ERROR', 'Не удалось соединиться с AI-сервисом');
  }
  if (!response.ok) {
    throw await parseErrorResponse(response);
  }
  if (response.status === 204) return null;
  return response.json();
}

/** UC-2: сводка перед звонком — факты из данных + текст от LLM. */
export function fetchBrief(clientId) {
  return aiFetch(`/clients/${clientId}/brief`);
}

/** UC-3: приоритетная выборка (админ): {prompt, limit} → критерии + список с баллами. */
export function prioritize(payload) {
  return aiFetch('/prioritize', { method: 'POST', body: payload });
}

/** UC-4: агрегаты для дашбордов. */
export function fetchAnalyticsSummary() {
  return aiFetch('/analytics/summary');
}

export function fetchAnalyticsDynamics() {
  return aiFetch('/analytics/dynamics');
}

export function fetchAnalyticsTopDebt(limit = 10) {
  return aiFetch(`/analytics/top-debt?limit=${limit}`);
}

export function fetchAnalyticsOperators() {
  return aiFetch('/analytics/operators');
}

/** UC-5: экспорт выборки/отчёта в xlsx — скачивание файла на клиенте. */
export async function exportXlsx(payload, filename) {
  const headers = aiHeaders();
  headers['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(`${AI_BASE_URL}/export/xlsx`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
    });
  } catch {
    throw new AiError(0, 'NETWORK_ERROR', 'Не удалось соединиться с AI-сервисом');
  }
  if (!response.ok) {
    throw await parseErrorResponse(response);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * WebSocket чата (UC-1). Возвращает готовый WebSocket; URL собирается из базового
 * адреса AI-сервиса. Живёт в пределах одной сессии чата — переподключение начинает
 * новую историю (история хранится в памяти соединения).
 */
export function openChatSocket() {
  const wsBase = AI_BASE_URL.replace(/^http/, 'ws').replace(/\/api\/v1\/ai$/, '');
  const token = encodeURIComponent(getAccessToken() ?? '');
  return new WebSocket(`${wsBase}/api/v1/ai/chat?token=${token}`);
}
