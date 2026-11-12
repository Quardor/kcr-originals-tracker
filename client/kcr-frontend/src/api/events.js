import { apiFetch } from './client';

function buildQuery(params) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue;
    query.append(key, String(value));
  }
  const qs = query.toString();
  return qs ? `?${qs}` : '';
}

/**
 * История клиента — новые события к старым, постранично.
 * @returns {Promise<{content: Array, page: number, size: number, totalElements: number, totalPages: number}>}
 */
export function fetchEvents(clientId, { page = 0, size = 20 } = {}) {
  return apiFetch(`/clients/${clientId}/events${buildQuery({ page, size })}`);
}

/** Создать событие вручную: CALL/LETTER_SENT/COMMENT (системные типы — только сервис). */
export function createEvent(clientId, body) {
  return apiFetch(`/clients/${clientId}/events`, { method: 'POST', body });
}
