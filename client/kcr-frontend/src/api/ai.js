import { API_BASE_URL, apiFetch } from './client';

/**
 * Клиент ИИ-помощника (kcr-ml): фронт обращается только к бэкенду — те же токены и тот же
 * контракт ошибок, что у остального API. Бэкенд проверяет права и проксирует запрос в kcr-ml
 * (домен ai/, ARCHITECTURE.md бэкенда §5).
 */
const AI_PATH = '/ai';

/** UC-2: сводка перед звонком — факты из данных + текст от LLM. */
export function fetchBrief(clientId) {
  return apiFetch(`${AI_PATH}/clients/${clientId}/brief`);
}

/** UC-3: приоритетная выборка (админ): {prompt, limit} → критерии + список с баллами. */
export function prioritize(payload) {
  return apiFetch(`${AI_PATH}/prioritize`, { method: 'POST', body: payload });
}

/** UC-4: агрегаты для дашбордов. */
export function fetchAnalyticsSummary() {
  return apiFetch(`${AI_PATH}/analytics/summary`);
}

export function fetchAnalyticsDynamics() {
  return apiFetch(`${AI_PATH}/analytics/dynamics`);
}

export function fetchAnalyticsTopDebt(limit = 10) {
  return apiFetch(`${AI_PATH}/analytics/top-debt?limit=${limit}`);
}

export function fetchAnalyticsOperators() {
  return apiFetch(`${AI_PATH}/analytics/operators`);
}

/** UC-5: экспорт выборки/отчёта в xlsx — скачивание файла на клиенте. */
export async function exportXlsx(payload, filename) {
  const blob = await apiFetch(`${AI_PATH}/export/xlsx`, {
    method: 'POST',
    body: payload,
    responseType: 'blob',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

// WebSocket требует абсолютный URL: для относительного базового адреса (сборка под nginx)
// origin берётся у самой страницы.
function chatOrigin() {
  if (API_BASE_URL.startsWith('/')) {
    return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}`;
  }
  return API_BASE_URL.replace(/^http/, 'ws').replace(/\/api\/v1$/, '');
}

/**
 * WebSocket чата (UC-1). Браузер не может поставить заголовок Authorization на WebSocket,
 * поэтому соединение открывается по одноразовому ticket, который выдаёт бэкенд: он живёт
 * 30 секунд и расходуется на одно рукопожатие, токен в URL не встречается. Возвращает
 * готовый WebSocket. Живёт в пределах одной сессии чата — переподключение начинает новую
 * историю (история хранится в памяти соединения).
 */
export async function openChatSocket() {
  const { ticket } = await apiFetch(`${AI_PATH}/chat/ticket`, { method: 'POST' });
  return new WebSocket(`${chatOrigin()}/api/v1/ai/chat?ticket=${encodeURIComponent(ticket)}`);
}
