import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../api/errors';

export default function LoginPage() {
  const { user, login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (user) {
    return <Navigate to={location.state?.from?.pathname ?? '/clients'} replace />;
  }

  const isRegister = mode === 'register';

  const submit = async (event) => {
    event.preventDefault();
    setError(null);

    if (isRegister) {
      if (username.trim().length < 3) return setError('Логин — минимум 3 символа');
      if (password.length < 8) return setError('Пароль — минимум 8 символов');
    }
    setBusy(true);
    try {
      if (isRegister) {
        await register({ username: username.trim(), password, email: email.trim() || undefined });
      } else {
        await login(username.trim(), password);
      }
      navigate(location.state?.from?.pathname ?? '/clients', { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit}>
        <h1 className="login-title">
          KCR <span>Originals Tracker</span>
        </h1>
        <p className="login-subtitle">Учёт оригиналов документов</p>

        <div className="login-tabs">
          <button
            type="button"
            className={`login-tab ${!isRegister ? 'active' : ''}`}
            onClick={() => { setMode('login'); setError(null); }}
          >
            Вход
          </button>
          <button
            type="button"
            className={`login-tab ${isRegister ? 'active' : ''}`}
            onClick={() => { setMode('register'); setError(null); }}
          >
            Регистрация
          </button>
        </div>

        <label className="field">
          <span>Логин</span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
          />
        </label>

        <label className="field">
          <span>Пароль</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            required
          />
        </label>

        {isRegister && (
          <label className="field">
            <span>Email (необязательно)</span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
            />
          </label>
        )}

        {error && <div className="error-box" role="alert">{error}</div>}

        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Подождите…' : isRegister ? 'Зарегистрироваться' : 'Войти'}
        </button>

        {isRegister && (
          <p className="login-note">Регистрация создаёт аккаунт оператора.</p>
        )}
      </form>
    </div>
  );
}
