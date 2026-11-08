import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

// Родительский элемент вложенных роутов: содержимое страниц приходит через <Outlet />,
// а не через children — иначе все вложенные страницы рендерятся пустыми.
export default function Layout() {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <Link to="/clients" className="brand">
            KCR<span>Originals Tracker</span>
          </Link>
          <nav className="nav">
            <NavLink to="/clients">Клиенты</NavLink>
            <NavLink to="/today">Сегодня</NavLink>
            {isAdmin && <NavLink to="/import">Импорт</NavLink>}
          </nav>
          <div className="header-user">
            <span className="user-name">{user?.username}</span>
            <span className={`badge badge-role-${(user?.role ?? '').toLowerCase()}`}>
              {user?.role === 'ADMIN' ? 'Администратор' : 'Оператор'}
            </span>
            <button type="button" className="btn btn-ghost" onClick={handleLogout}>
              Выйти
            </button>
          </div>
        </div>
      </header>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
