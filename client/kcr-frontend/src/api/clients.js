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
 * Страница клиентов.
 * @param {object} params search, clientType, kcrActive, kcrBlocked, flag, stage,
 *   assigneeId, unassigned (true — только свободные),
 *   page (>=0), size (1..100), sortBy (id|kcrCode|inn|organizationName|stage|nextContactAt),
 *   sortDir (asc|desc)
 * @returns {Promise<{content: Array, page: number, size: number, totalElements: number, totalPages: number}>}
 */
export function fetchClients(params) {
  return apiFetch(`/clients${buildQuery(params)}`);
}

export function fetchClient(id) {
  return apiFetch(`/clients/${id}`);
}

/** История срезов клиента; reportingPeriod — необязательный фильтр точного совпадения. */
export function fetchClientSnapshots(id, reportingPeriod) {
  return apiFetch(`/clients/${id}/snapshots${buildQuery({ reportingPeriod })}`);
}

export function createClient(body) {
  return apiFetch('/clients', { method: 'POST', body });
}

/** PATCH: отсутствующие поля (null) сервер не меняет. */
export function updateClient(id, body) {
  return apiFetch(`/clients/${id}`, { method: 'PATCH', body });
}

/** Атомарно взять свободного клиента (оператор и админ). */
export function takeClient(id) {
  return apiFetch(`/clients/${id}/take`, { method: 'POST' });
}

/**
 * Переназначение ответственного (админ): assigneeId = id | null (null — освободить).
 * Эндпоинт одноцелевой: тело применяется целиком.
 */
export function updateAssignment(id, body) {
  return apiFetch(`/clients/${id}/assignment`, { method: 'PATCH', body });
}

/** Смена этапа: админ — любой, оператор — рабочие этапы своих клиентов. */
export function updateStage(id, body) {
  return apiFetch(`/clients/${id}/stage`, { method: 'PATCH', body });
}
