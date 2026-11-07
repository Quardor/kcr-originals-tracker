import { apiFetch } from './client';

/** Вход: { accessToken, refreshToken } */
export function login(username, password) {
  return apiFetch('/auth/login', { method: 'POST', body: { username, password }, auth: false });
}

/** Регистрация (роль всегда OPERATOR): { accessToken, refreshToken } */
export function register({ username, password, email }) {
  const body = { username, password };
  if (email) body.email = email;
  return apiFetch('/auth/register', { method: 'POST', body, auth: false });
}

/** Выход — отзыв всех refresh-токенов пользователя, 204 */
export function logout() {
  return apiFetch('/auth/logout', { method: 'POST' });
}
