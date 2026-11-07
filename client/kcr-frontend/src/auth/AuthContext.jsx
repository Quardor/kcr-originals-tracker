import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import * as authApi from '../api/auth';
import { clearTokens, getAccessToken, setTokens, SESSION_EXPIRED_EVENT } from '../api/client';
import { decodeUser } from './jwt';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => decodeUser(getAccessToken()));

  // Refresh закончился неудачей — токены стёрты, сессии больше нет.
  // Событие storage синхронизирует сессию между вкладками (общий localStorage):
  // выход/вход в одной вкладке подтягивается остальными.
  useEffect(() => {
    const onSessionExpired = () => setUser(null);
    const onStorage = (event) => {
      if (event.key === null || event.key.startsWith('kcr.')) {
        setUser(decodeUser(getAccessToken()));
      }
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  const applyAuthResponse = useCallback((data) => {
    setTokens(data.accessToken, data.refreshToken);
    setUser(decodeUser(data.accessToken));
  }, []);

  const login = useCallback(
    async (username, password) => {
      applyAuthResponse(await authApi.login(username, password));
    },
    [applyAuthResponse]
  );

  const register = useCallback(
    async (payload) => {
      applyAuthResponse(await authApi.register(payload));
    },
    [applyAuthResponse]
  );

  const logout = useCallback(async () => {
    try {
      // Отзыв всех refresh-токенов; неудача (например, истёкший access) сессию всё равно чистит.
      await authApi.logout();
    } catch {
      // игнорируем — локально выходим в любом случае
    }
    clearTokens();
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({
      user,
      isAdmin: user?.role === 'ADMIN',
      login,
      register,
      logout,
    }),
    [user, login, register, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth должен использоваться внутри AuthProvider');
  }
  return context;
}
