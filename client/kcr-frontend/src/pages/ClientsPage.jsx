import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchClients } from '../api/clients';
import { errorMessage } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import Pagination from '../components/Pagination';
import { ClientTypeBadge, ErrorBox, Spinner, TriStateBadge } from '../components/ui';

const SORT_FIELDS = [
  { value: 'kcrCode', label: 'Код KCR' },
  { value: 'organizationName', label: 'Организация' },
  { value: 'inn', label: 'ИНН' },
  { value: 'id', label: 'ID' },
];

const CLIENT_TYPES = [
  { value: '', label: 'Любой' },
  { value: 'VIP', label: 'VIP' },
  { value: 'TECH_PARTNER', label: 'Техпартнёр' },
  { value: 'WORKS_VIA_MP', label: 'Через МП' },
  { value: 'NEWCOMER', label: 'Новичок' },
];

const TRI_STATE = [
  { value: '', label: 'Любой' },
  { value: 'true', label: 'Да' },
  { value: 'false', label: 'Нет' },
];

export default function ClientsPage() {
  const { isAdmin } = useAuth();

  const [search, setSearch] = useState('');
  const [clientType, setClientType] = useState('');
  const [kcrActive, setKcrActive] = useState('');
  const [kcrBlocked, setKcrBlocked] = useState('');
  const [flag, setFlag] = useState('');
  const [sortBy, setSortBy] = useState('kcrCode');
  const [sortDir, setSortDir] = useState('asc');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [debouncedSearch, setDebouncedSearch] = useState('');
  const searchTimer = useRef(null);

  // Поиск — с задержкой, чтобы не дёргать API на каждый символ.
  useEffect(() => {
    clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => {
      setDebouncedSearch(search.trim());
      setPage(0);
    }, 400);
    return () => clearTimeout(searchTimer.current);
  }, [search]);

  const load = useCallback(
    () =>
      fetchClients({
        search: debouncedSearch || undefined,
        clientType: clientType || undefined,
        kcrActive: kcrActive === '' ? undefined : kcrActive === 'true',
        kcrBlocked: kcrBlocked === '' ? undefined : kcrBlocked === 'true',
        flag: flag.trim() || undefined,
        sortBy,
        sortDir,
        page,
        size,
      }),
    [debouncedSearch, clientType, kcrActive, kcrBlocked, flag, sortBy, sortDir, page, size]
  );

  // Фильтры меняются быстро: отменяем применение ответов устаревших запросов.
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

  const changeFilter = (setter) => (value) => {
    setter(value);
    setPage(0);
  };

  const toggleSort = (field) => {
    if (sortBy === field) {
      setSortDir((dir) => (dir === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortBy(field);
      setSortDir('asc');
    }
    setPage(0);
  };

  const sortIndicator = (field) =>
    sortBy === field ? (sortDir === 'asc' ? ' ↑' : ' ↓') : '';

  return (
    <>
      <div className="page-head">
        <h1>Клиенты</h1>
        {isAdmin && (
          <Link to="/clients/new" className="btn btn-primary">
            + Добавить клиента
          </Link>
        )}
      </div>

      <div className="filters">
        <input
          className="filter-search"
          placeholder="Поиск: код KCR или ИНН…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select value={clientType} onChange={(e) => changeFilter(setClientType)(e.target.value)}>
          {CLIENT_TYPES.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
        <select value={kcrActive} onChange={(e) => changeFilter(setKcrActive)(e.target.value)}>
          {TRI_STATE.map((t) => (
            <option key={`a-${t.value}`} value={t.value}>KCR действует: {t.label}</option>
          ))}
        </select>
        <select value={kcrBlocked} onChange={(e) => changeFilter(setKcrBlocked)(e.target.value)}>
          {TRI_STATE.map((t) => (
            <option key={`b-${t.value}`} value={t.value}>KCR заблокирован: {t.label}</option>
          ))}
        </select>
        <input
          className="filter-flag"
          placeholder="Признак (напр. VIP)"
          value={flag}
          onChange={(e) => changeFilter(setFlag)(e.target.value)}
        />
        <select value={sortBy} onChange={(e) => changeFilter(setSortBy)(e.target.value)}>
          {SORT_FIELDS.map((f) => (
            <option key={f.value} value={f.value}>Сортировка: {f.label}</option>
          ))}
        </select>
        <select value={sortDir} onChange={(e) => changeFilter(setSortDir)(e.target.value)}>
          <option value="asc">По возрастанию</option>
          <option value="desc">По убыванию</option>
        </select>
      </div>

      <ErrorBox message={error} />

      {loading && !data ? (
        <Spinner />
      ) : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th className="th-sortable" onClick={() => toggleSort('kcrCode')}>
                    Код KCR{sortIndicator('kcrCode')}
                  </th>
                  <th className="th-sortable" onClick={() => toggleSort('organizationName')}>
                    Организация{sortIndicator('organizationName')}
                  </th>
                  <th className="th-sortable" onClick={() => toggleSort('inn')}>
                    ИНН{sortIndicator('inn')}
                  </th>
                  <th>Тип</th>
                  <th>KCR действует</th>
                  <th>KCR заблокирован</th>
                  <th>Менеджер</th>
                </tr>
              </thead>
              <tbody>
                {data?.content?.length === 0 && (
                  <tr>
                    <td colSpan={7} className="table-empty">Ничего не найдено</td>
                  </tr>
                )}
                {data?.content?.map((client) => (
                  <tr key={client.id}>
                    <td>
                      <Link className="cell-link" to={`/clients/${client.id}`}>
                        {client.kcrCode}
                      </Link>
                    </td>
                    <td className="cell-wide">{client.organizationName ?? '—'}</td>
                    <td>{client.inn}</td>
                    <td><ClientTypeBadge type={client.clientType} /></td>
                    <td><TriStateBadge value={client.kcrActive} positive="да" negative="нет" /></td>
                    <td><TriStateBadge value={client.kcrBlocked} positive="да" negative="нет" /></td>
                    <td>{client.manager ?? '—'}</td>
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
