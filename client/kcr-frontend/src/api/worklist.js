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
