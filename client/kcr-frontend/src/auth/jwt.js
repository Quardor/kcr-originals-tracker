/**
 * Декод payload access-токена — только для отображения (имя, роль) и маршрутизации UI.
 * Настоящая проверка прав выполняет бэкенд; спрятать здесь нельзя ничего ценного.
 * Claims бэкенда: sub (id), preferred_username, email, roles (строка ADMIN|OPERATOR), jti.
 */
export function decodeUser(accessToken) {
  if (!accessToken) return null;
  try {
    const payloadPart = accessToken.split('.')[1];
    const json = decodeURIComponent(
      atob(payloadPart.replace(/-/g, '+').replace(/_/g, '/'))
        .split('')
        .map((ch) => `%${ch.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join('')
    );
    const claims = JSON.parse(json);
    return {
      username: claims.preferred_username ?? '',
      role: typeof claims.roles === 'string' ? claims.roles : null,
    };
  } catch {
    return null;
  }
}
