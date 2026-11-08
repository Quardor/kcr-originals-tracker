import { apiFetch } from './client';

/** Претензии клиента с вложенными санкциями (карточка, обе роли). */
export function fetchClaims(clientId) {
  return apiFetch(`/clients/${clientId}/claims`);
}

/** Регистрация претензии (админ). */
export function createClaim(clientId, body) {
  return apiFetch(`/clients/${clientId}/claims`, { method: 'POST', body });
}

/** Изменение претензии — PATCH: отсутствующие поля сервер не меняет (админ). */
export function updateClaim(claimId, body) {
  return apiFetch(`/claims/${claimId}`, { method: 'PATCH', body });
}

/** Добавление санкции по претензии (админ). */
export function addSanction(claimId, body) {
  return apiFetch(`/claims/${claimId}/sanctions`, { method: 'POST', body });
}

/** Изменение санкции — PATCH (админ). */
export function updateSanction(sanctionId, body) {
  return apiFetch(`/sanctions/${sanctionId}`, { method: 'PATCH', body });
}

/** Справочник пользователей для переназначения ответственного (админ). */
export function fetchUsers() {
  return apiFetch('/users');
}
