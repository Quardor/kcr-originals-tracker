import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div className="not-found">
      <h1>404</h1>
      <p>Страница не найдена.</p>
      <Link to="/clients" className="btn">На список клиентов</Link>
    </div>
  );
}
