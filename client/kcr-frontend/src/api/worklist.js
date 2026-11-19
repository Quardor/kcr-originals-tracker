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

/** «Сегодня нужно»: срок следующего контакта наступил; оператору — свои, админу — все. */
export function fetchTodayWorklist({ page = 0, size = 20 } = {}) {
  return apiFetch(`/worklist/today${buildQuery({ page, size })}`);
}

/** Предпросмотр списка на обзвон по фильтрам (только админ). */
export function previewCallList(filters) {
  return apiFetch('/worklist/preview', {
    method: 'POST',
    body: filters,
  });
}

/** Закрепить составленный список на обзвон для всех операторов (только админ). */
export function generateCallList(payload) {
  return apiFetch('/worklist/generate', {
    method: 'POST',
    body: payload,
  });
}

/** Получить единый закреплённый список на обзвон (доступен операторам и админам). */
export function fetchCallList() {
  return apiFetch('/worklist/call-list');
}

/** Очистить закреплённый список на обзвон (только админ). */
export function clearCallList() {
  return apiFetch('/worklist/call-list', {
    method: 'DELETE',
  });
}
