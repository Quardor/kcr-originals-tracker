import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchUsers } from '../api/claims';
import { fetchClient, fetchClientSnapshots, takeClient, updateAssignment, updateStage } from '../api/clients';
import { errorMessage } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import BriefSection from '../components/BriefSection';
import ClaimsSection from '../components/ClaimsSection';
import EventForm from '../components/EventForm';
import HistoryFeed from '../components/HistoryFeed';
import { ClientTypeBadge, ErrorBox, Spinner, TriStateBadge } from '../components/ui';
import { WORK_STAGES, WORK_STAGES_OPERATOR, stageLabel } from '../labels';

function Field({ label, children }) {
  return (
    <div className="detail-field">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

export default function ClientCardPage() {
  const { id } = useParams();
  const { user, isAdmin } = useAuth();

  const [client, setClient] = useState(null);
  const [snapshots, setSnapshots] = useState(null);
  const [periodFilter, setPeriodFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [eventsVersion, setEventsVersion] = useState(0);
  const [stageDraft, setStageDraft] = useState('');
  const [users, setUsers] = useState(null);
  const [assigneeDraft, setAssigneeDraft] = useState('');

  const ownClient = client != null && client.assigneeId != null && client.assigneeId === user?.id;
  const stageOptions = isAdmin ? WORK_STAGES : ownClient ? WORK_STAGES_OPERATOR : [];

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([fetchClient(id), fetchClientSnapshots(id)])
      .then(([clientData, snapshotsData]) => {
        if (cancelled) return;
        setClient(clientData);
        setSnapshots(snapshotsData);
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
  }, [id]);

  useEffect(() => {
    if (!isAdmin || users) return undefined;
    let cancelled = false;
    fetchUsers()
      .then((list) => {
        if (!cancelled) setUsers(list);
      })
      .catch(() => {
        // список не критичен: переназначение останется недоступным до перезагрузки
      });
    return () => {
      cancelled = true;
    };
  }, [isAdmin, users]);

  const loadSnapshots = useCallback(async () => {
    try {
      setSnapshots(await fetchClientSnapshots(id, periodFilter.trim() || undefined));
    } catch (err) {
      setError(errorMessage(err));
    }
  }, [id, periodFilter]);

  useEffect(() => {
    if (client) {
      const timer = setTimeout(loadSnapshots, periodFilter ? 400 : 0);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [loadSnapshots, client, periodFilter]);

  const reloadClient = useCallback(async () => {
    try {
      setClient(await fetchClient(id));
    } catch (err) {
      setError(errorMessage(err));
    }
  }, [id]);

  const run = async (action) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await reloadClient();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const onEventCreated = () => {
    setEventsVersion((v) => v + 1);
    reloadClient();
  };

  if (loading) return <Spinner />;
  if (error && !client) return <ErrorBox message={error} />;
  if (!client) return null;

  return (
    <>
      <div className="page-head">
        <div>
          <Link to="/clients" className="back-link">← К списку клиентов</Link>
          <h1>
            {client.kcrCode}
            <span className="h1-sub"> {client.organizationName}</span>
          </h1>
        </div>
        {isAdmin && (
          <Link to={`/clients/${client.id}/edit`} className="btn">
            Изменить
          </Link>
        )}
      </div>

      <ErrorBox message={error} />

      <section className="card">
        <div className="section-head">
          <h2>Работа</h2>
          {client.assigneeId == null && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={() => run(() => takeClient(client.id))}
            >
              Взять в работу
            </button>
          )}
        </div>
        <dl className="details-grid">
          <Field label="Этап">
            <span className={`badge badge-stage-${(client.stage ?? '').toLowerCase()}`}>
              {stageLabel(client.stage)}
            </span>
          </Field>
          <Field label="Ответственный">
            {client.assigneeUsername ?? <span className="muted">не взят в работу</span>}
          </Field>
          <Field label="Следующий контакт">{client.nextContactAt ?? '—'}</Field>
          <Field label="В сервисе с">
            {client.createdAt ? new Date(client.createdAt).toLocaleDateString('ru-RU') : '—'}
          </Field>
        </dl>

        {stageOptions.length > 0 && (
          <div className="work-controls">
            <select value={stageDraft} onChange={(e) => setStageDraft(e.target.value)}>
              <option value="">— сменить этап —</option>
              {stageOptions.map((s) => (
                <option key={s.value} value={s.value}>{s.label}</option>
              ))}
            </select>
            <button
              type="button"
              className="btn"
              disabled={busy || !stageDraft}
              onClick={() =>
                run(async () => {
                  await updateStage(client.id, { stage: stageDraft });
                  setStageDraft('');
                })
              }
            >
              Сменить этап
            </button>
            {isAdmin && users && (
              <>
                <select
                  value={assigneeDraft}
                  onChange={(e) => setAssigneeDraft(e.target.value)}
                  title="Переназначение ответственного"
                >
                  <option value="">— ответственный —</option>
                  {users.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.username} ({u.role === 'ADMIN' ? 'админ' : 'оператор'})
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn"
                  disabled={busy || !assigneeDraft}
                  onClick={() =>
                    run(async () => {
                      await updateAssignment(client.id, { assigneeId: Number(assigneeDraft) });
                      setAssigneeDraft('');
                    })
                  }
                >
                  Назначить
                </button>
                {client.assigneeId != null && (
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={busy}
                    onClick={() => run(() => updateAssignment(client.id, { assigneeId: null }))}
                  >
                    Освободить
                  </button>
                )}
              </>
            )}
          </div>
        )}
        {client.assigneeId != null && !isAdmin && !ownClient && (
          <p className="hint">Клиент у другого оператора — смену этапа и переназначение делает администратор.</p>
        )}
      </section>

      <section className="card">
        <h2>Основное</h2>
        <dl className="details-grid">
          <Field label="Код KCR">{client.kcrCode}</Field>
          <Field label="ИНН">{client.inn}</Field>
          <Field label="КПП">{client.kpp ?? '—'}</Field>
          <Field label="Организация">{client.organizationName}</Field>
          <Field label="Внутренний ID / лицевой счёт">{client.internalId ?? '—'}</Field>
          <Field label="Тип клиента"><ClientTypeBadge type={client.clientType} /></Field>
        </dl>
      </section>

      <section className="card">
        <h2>Статусы и признаки</h2>
        <dl className="details-grid">
          <Field label="KCR действует">
            <TriStateBadge value={client.kcrActive} positive="действует" negative="не действует" />
          </Field>
          <Field label="KCR заблокирован">
            <TriStateBadge value={client.kcrBlocked} positive="заблокирован" negative="не заблокирован" />
          </Field>
          <Field label="Тариф">{client.tariff ?? '—'}</Field>
          <Field label="Время от Екатеринбурга">{client.ekbTravelTime ?? '—'}</Field>
          <Field label="Менеджер">{client.manager ?? '—'}</Field>
          <Field label="Специальные признаки">
            {client.flags?.length ? (
              <span className="flags-list">
                {client.flags.map((f) => (
                  <span key={f} className="badge badge-flag">{f}</span>
                ))}
              </span>
            ) : (
              '—'
            )}
          </Field>
        </dl>
      </section>

      <section className="card">
        <h2>Контакты</h2>
        <dl className="details-grid">
          <Field label="Контактное лицо">{client.contactPerson ?? '—'}</Field>
          <Field label="Телефон">{client.contactPhone ?? '—'}</Field>
          <Field label="Email">
            {client.contactEmail ? <a href={`mailto:${client.contactEmail}`}>{client.contactEmail}</a> : '—'}
          </Field>
        </dl>
      </section>

      <BriefSection clientId={client.id} />

      <section className="card">
        <h2>История работы</h2>
        <EventForm
          clientId={client.id}
          client={client}
          isAdmin={isAdmin}
          currentUserId={user?.id ?? null}
          onCreated={onEventCreated}
        />
        <HistoryFeed clientId={client.id} version={eventsVersion} />
      </section>

      <section className="card">
        <h2>Претензии и санкции</h2>
        <ClaimsSection clientId={client.id} isAdmin={isAdmin} version={eventsVersion} />
      </section>

      <section className="card">
        <div className="section-head">
          <h2>Срезы передачи документов</h2>
          <input
            className="filter-period"
            placeholder="Фильтр по периоду (напр. 1 квартал 2026)"
            value={periodFilter}
            onChange={(e) => setPeriodFilter(e.target.value)}
          />
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Отчётный период</th>
                <th>Дата среза</th>
                <th>Всего документов</th>
                <th>Не передано</th>
                <th className="col-percent">Доля непереданных</th>
              </tr>
            </thead>
            <tbody>
              {snapshots?.length === 0 && (
                <tr>
                  <td colSpan={5} className="table-empty">Срезов пока нет</td>
                </tr>
              )}
              {snapshots?.map((snapshot) => (
                <tr key={snapshot.id}>
                  <td>{snapshot.reportingPeriod}</td>
                  <td>{snapshot.snapshotDate}</td>
                  <td>{snapshot.totalDocuments ?? '—'}</td>
                  <td>{snapshot.notTransferredDocuments}</td>
                  <td className="col-percent">
                    {snapshot.percentNotTransferred === null || snapshot.percentNotTransferred === undefined ? (
                      <span className="muted">—</span>
                    ) : (
                      <div className="percent-cell">
                        <div className="progress">
                          <div
                            className={`progress-fill ${snapshot.percentNotTransferred >= 5 ? 'progress-warn' : ''}`}
                            style={{ width: `${Math.min(snapshot.percentNotTransferred, 100)}%` }}
                          />
                        </div>
                        <span>{snapshot.percentNotTransferred}%</span>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="hint">
          Порог включения в обзвон — доля непереданных от 5% (кроме новичков). Срезы добавляются
          импортом и никогда не перезаписываются.
        </p>
      </section>
    </>
  );
}
