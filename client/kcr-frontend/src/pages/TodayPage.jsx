import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchTodayWorklist } from '../api/worklist';
import { errorMessage } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import Pagination from '../components/Pagination';
import { ErrorBox, Spinner } from '../components/ui';
import { stageLabel } from '../labels';

/**
 * «Сегодня нужно» (сценарий ТЗ): клиенты, по которым наступил срок следующего
 * действия. Оператор видит своих, администратор — всех.
 */
export default function TodayPage() {
  const { isAdmin } = useAuth();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(() => fetchTodayWorklist({ page, size }), [page, size]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    load()
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (!cancelled) setError(errorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [load]);

  const today = new Date().toLocaleDateString('ru-RU');

  return (
    <>
      <div className="page-head">
        <h1>Сегодня нужно</h1>
      </div>
      <p className="hint">
        Клиенты с наступившим сроком следующего контакта ({today}).
        {isAdmin ? ' Администратор видит всех, оператор — только своих клиентов.' : ''}
      </p>

      <ErrorBox message={error} />

      {loading && !data ? (
        <Spinner />
      ) : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Следующий контакт</th>
                  <th>Код KCR</th>
                  <th>Организация</th>
                  <th>Этап</th>
                  <th>Ответственный</th>
                </tr>
              </thead>
              <tbody>
                {data?.content?.length === 0 && (
                  <tr>
                    <td colSpan={5} className="table-empty">
                      Просроченных контактов нет — всё под контролем
                    </td>
                  </tr>
                )}
                {data?.content?.map((client) => (
                  <tr key={client.id}>
                    <td>{client.nextContactAt ?? '—'}</td>
                    <td>
                      <Link className="cell-link" to={`/clients/${client.id}`}>
                        {client.kcrCode}
                      </Link>
                    </td>
                    <td className="cell-wide">{client.organizationName ?? '—'}</td>
                    <td><span className={`badge badge-stage-${(client.stage ?? '').toLowerCase()}`}>{stageLabel(client.stage)}</span></td>
                    <td>{client.assigneeUsername ?? <span className="muted">не взят</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data && (
            <Pagination
              page={data.page}
              size={data.size}
              totalElements={data.totalElements}
              totalPages={data.totalPages}
              onPage={setPage}
              onSize={(newSize) => {
                setSize(newSize);
                setPage(0);
              }}
            />
          )}
        </>
      )}
    </>
  );
}
