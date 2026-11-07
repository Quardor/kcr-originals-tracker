const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';

const ACCESS_TOKEN_KEY = 'kcr.accessToken';
const REFRESH_TOKEN_KEY = 'kcr.refreshToken';

// Событие рассылается при безуспешном refresh: сессия закончилась, UI уводит на /login.
export const SESSION_EXPIRED_EVENT = 'kcr:session-expired';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken, refreshToken) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

async function parseErrorResponse(response) {
  let code = null;
  let message = `HTTP ${response.status}`;
  try {
    const body = await response.json();
    if (body && typeof body.code === 'string') code = body.code;
    if (body && typeof body.message === 'string' && body.message) message = body.message;
  } catch {
    // тело не JSON (например, пустое у 204/502) — остаётся дефолт
  }
  return new ApiError(response.status, code, message);
}

/**
 * Один параллельный refresh двумя запросами = повторное предъявление ротированного
 * токена: бэкенд отзывает всё семейство (reuse-detection). Поэтому refresh всегда
 * single-flight — параллельные 401 в пределах вкладки ждут один и тот же промис.
 */
let refreshPromise = null;

async function refreshTokens() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new ApiError(401, 'AUTHENTICATION_REQUIRED', 'Сессия истекла');
  }
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) {
    clearTokens();
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    throw await parseErrorResponse(response);
  }
  const data = await response.json();
  setTokens(data.accessToken, data.refreshToken);
  return data.accessToken;
}

function accessTokenAlive() {
  const token = getAccessToken();
  if (!token) return false;
  try {
    const claims = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof claims.exp === 'number' && claims.exp * 1000 > Date.now();
  } catch {
    return false;
  }
}

// Между вкладками single-flight через Web Locks: localStorage общий, но без блокировки
// две вкладки с истёкшим access-током отправят refresh параллельно — вторая предъявит
// уже ротированный refresh-токен, и семейство будет отозвано у обеих.
async function refreshCrossTab() {
  if (typeof navigator === 'undefined' || !navigator.locks?.request) {
    return refreshTokens();
  }
  return navigator.locks.request('kcr-refresh', async () => {
    if (accessTokenAlive()) return getAccessToken(); // уже обновила другая вкладка
    return refreshTokens();
  });
}

function refreshSingleFlight() {
  if (!refreshPromise) {
    refreshPromise = refreshCrossTab().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

/**
 * Базовый запрос к API.
 *
 * @param {string} path путь от базового URL, начинается с '/'
 * @param {object} options
 * @param {string} [options.method] HTTP-метод, по умолчанию GET
 * @param {object} [options.body] JSON-тело (сериализуется автоматически)
 * @param {FormData} [options.formData] multipart-тело (Content-Type выставляет браузер)
 * @param {boolean} [options.auth] прикреплять ли Authorization и делать refresh по 401
 */
export async function apiFetch(path, { method = 'GET', body, formData, auth = true } = {}) {
  const doFetch = () => {
    const headers = {};
    if (auth && getAccessToken()) {
      headers.Authorization = `Bearer ${getAccessToken()}`;
    }
    let payload;
    if (formData) {
      payload = formData;
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    return fetch(`${BASE_URL}${path}`, { method, headers, body: payload });
  };

  let response;
  try {
    response = await doFetch();
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Не удалось соединиться с сервером');
  }

  // Просроченный access-токен: обновляем пару один раз и повторяем запрос.
  if (response.status === 401 && auth && getRefreshToken() && path !== '/auth/refresh') {
    try {
      await refreshSingleFlight();
    } catch (error) {
      throw error;
    }
    response = await doFetch();
  }

  if (!response.ok) {
    throw await parseErrorResponse(response);
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
}
