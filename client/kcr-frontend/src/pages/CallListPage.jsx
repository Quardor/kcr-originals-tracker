import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { clearCallList, fetchCallList, generateCallList, previewCallList } from '../api/worklist';
import { takeClient } from '../api/clients';
import { errorMessage } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import { DynamicsBadge, ErrorBox, Spinner } from '../components/ui';
import { DYNAMICS_CATEGORY_LABELS, formatDateTime, stageLabel } from '../labels';

const CLIENT_TYPES = [
  { value: '', label: 'Любой' },
  { value: 'VIP', label: 'VIP' },
  { value: 'TECH_PARTNER', label: 'Техпартнёр' },
  { value: 'WORKS_VIA_MP', label: 'Через МП' },
  { value: 'NEWCOMER', label: 'Новичок' },
];

export default function CallListPage() {
  const { user, isAdmin } = useAuth();

  // Режим просмотра для админа: 'list' (закреплённый список) или 'compose' (составление / предпросмотр)
  const [activeTab, setActiveTab] = useState('list');

  // Данные закреплённого списка
  const [callList, setCallList] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [successMessage, setSuccessMessage] = useState(null);

  // Фильтры для составления списка (админ)
  const [hasDebt, setHasDebt] = useState(true);
  const [minDebt, setMinDebt] = useState(1);
  const [minUntransferredPercent, setMinUntransferredPercent] = useState(5.0);
  const [maxUntransferredPercent, setMaxUntransferredPercent] = useState('');
  const [lowDynamics, setLowDynamics] = useState(true);
  const [dynamicsCategory, setDynamicsCategory] = useState('');
  const [clientType, setClientType] = useState('');
  const [search, setSearch] = useState('');

  // Предпросмотр
  const [previewClients, setPreviewClients] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [generating, setGenerating] = useState(false);

  // Действие взятия в работу
  const [takingClientId, setTakingClientId] = useState(null);

  const loadActiveCallList = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchCallList();
      setCallList(data);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadActiveCallList();
  }, [loadActiveCallList]);

  // Запуск предпросмотра
  const handlePreview = async (e) => {
    if (e) e.preventDefault();
    setPreviewLoading(true);
    setError(null);
    setSuccessMessage(null);
    try {
      const filters = {
        hasDebt,
        minDebt: minDebt ? Number(minDebt) : null,
        minUntransferredPercent: minUntransferredPercent !== '' ? Number(minUntransferredPercent) : null,
        maxUntransferredPercent: maxUntransferredPercent !== '' ? Number(maxUntransferredPercent) : null,
        lowDynamics,
        dynamicsCategory: dynamicsCategory || null,
        clientType: clientType || null,
        search: search.trim() || null,
      };
      const result = await previewCallList(filters);
      setPreviewClients(result);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPreviewLoading(false);
    }
  };

  // Закрепить полученный список для всех операторов
  const handleGenerate = async () => {
    if (previewClients && previewClients.length === 0) return;
    setGenerating(true);
    setError(null);
    setSuccessMessage(null);
    try {
      const payload = {
        hasDebt,
        minDebt: minDebt ? Number(minDebt) : null,
        minUntransferredPercent: minUntransferredPercent !== '' ? Number(minUntransferredPercent) : null,
        maxUntransferredPercent: maxUntransferredPercent !== '' ? Number(maxUntransferredPercent) : null,
        lowDynamics,
        dynamicsCategory: dynamicsCategory || null,
        clientType: clientType || null,
        search: search.trim() || null,
      };
      const summary = await generateCallList(payload);
      setCallList(summary);
      setPreviewClients(null);
      setActiveTab('list');
      setSuccessMessage(
        `Список на обзвон успешно составлен и закреплён для всех операторов! Клиентов в списке: ${summary.totalClients}`
      );
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setGenerating(false);
    }
  };

  // Очистить список
  const handleClear = async () => {
    if (!window.confirm('Вы уверены, что хотите очистить закреплённый список на обзвон?')) {
      return;
    }
    setError(null);
    setSuccessMessage(null);
    try {
      await clearCallList();
      setCallList({ totalClients: 0, clients: [] });
      setSuccessMessage('Закреплённый список на обзвон очищен.');
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  // Взятие клиента в работу оператором
  const handleTakeClient = async (clientId) => {
    setTakingClientId(clientId);
    setError(null);
    try {
      await takeClient(clientId);
      await loadActiveCallList();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setTakingClientId(null);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Список на обзвон</h1>
          <p className="page-hint">
            Единый пул клиентов для работы операторов. Закрепляется администратором на основе
            долговой информации, процента непереданных и категорий динамики.
          </p>
        </div>

        {isAdmin && (
          <div className="call-list-actions">
            <button
              type="button"
              className={`btn ${activeTab === 'list' ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setActiveTab('list')}
            >
              Закреплённый список ({callList?.totalClients ?? 0})
            </button>
            <button
              type="button"
              className={`btn ${activeTab === 'compose' ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setActiveTab('compose')}
            >
              Составить список
            </button>
          </div>
        )}
      </div>

      <ErrorBox message={error} />

      {successMessage && (
        <div className="card" style={{ background: '#f0fdf4', border: '1px solid #86efac', color: '#166534', padding: '12px 16px', marginBottom: '16px' }}>
          ✓ {successMessage}
        </div>
      )}

      {/* Вкладка 1: Закреплённый список для всех операторов */}
      {activeTab === 'list' && (
        <>
          <div className="call-list-meta">
            <div>
              <strong>Единый закреплённый список на обзвон</strong>
              {callList?.updatedAt && (
                <span className="muted" style={{ marginLeft: '12px' }}>
                  Составлен: {callList.updatedByUsername ?? 'администратором'} ({formatDateTime(callList.updatedAt)})
                </span>
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <span className="preview-badge">Всего клиентов: {callList?.totalClients ?? 0}</span>
              {isAdmin && (callList?.totalClients ?? 0) > 0 && (
                <button type="button" className="btn btn-ghost btn-sm" onClick={handleClear}>
                  Очистить список
                </button>
              )}
            </div>
          </div>

          {loading ? (
            <Spinner label="Загрузка списка на обзвон…" />
          ) : (
            <div className="table-wrap card">
              <table className="table">
                <thead>
                  <tr>
                    <th>Код KCR</th>
                    <th>Организация</th>
                    <th>ИНН</th>
                    <th>Всего док.</th>
                    <th>Не передано</th>
                    <th>% долга</th>
                    <th>Динамика</th>
                    <th>Этап</th>
                    <th>Ответственный</th>
                    <th>Действие</th>
                  </tr>
                </thead>
                <tbody>
                  {callList?.clients?.length === 0 ? (
                    <tr>
                      <td colSpan={10} className="table-empty">
                        {isAdmin
                          ? 'Список на обзвон ещё не закреплён. Перейдите на вкладку «Составить список», чтобы отфильтровать и закрепить клиентов.'
                          : 'Список на обзвон ещё не составлен администратором.'}
                      </td>
                    </tr>
                  ) : (
                    callList?.clients?.map((client) => {
                      const isAssignedToMe = client.assigneeId != null && client.assigneeId === user?.id;
                      const isFree = client.assigneeId == null;

                      return (
                        <tr key={client.clientId}>
                          <td>
                            <Link className="cell-link" to={`/clients/${client.clientId}`}>
                              {client.kcrCode}
                            </Link>
                          </td>
                          <td className="cell-wide">{client.organizationName}</td>
                          <td>{client.inn}</td>
                          <td>{client.totalDocuments ?? '—'}</td>
                          <td>
                            <strong>{client.notTransferredDocuments}</strong>
                          </td>
                          <td>
                            {client.percentNotTransferred != null ? `${client.percentNotTransferred}%` : '—'}
                          </td>
                          <td>
                            <DynamicsBadge category={client.dynamicsCategory} />
                          </td>
                          <td>
                            <span className={`badge badge-stage-${(client.stage ?? '').toLowerCase()}`}>
                              {stageLabel(client.stage)}
                            </span>
                          </td>
                          <td>
                            {client.assigneeUsername ? (
                              <span className={isAssignedToMe ? 'badge badge-ok' : ''}>
                                {client.assigneeUsername}
                              </span>
                            ) : (
                              <span className="muted">Свободен</span>
                            )}
                          </td>
                          <td>
                            {isFree ? (
                              <button
                                type="button"
                                className="btn btn-sm btn-primary"
                                disabled={takingClientId === client.clientId}
                                onClick={() => handleTakeClient(client.clientId)}
                              >
                                {takingClientId === client.clientId ? 'Берём…' : 'Взять в работу'}
                              </button>
                            ) : isAssignedToMe ? (
                              <span className="muted">В вашей работе</span>
                            ) : (
                              <span className="muted">—</span>
                            )}
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {/* Вкладка 2: Составление списка администратором (фильтры + предпросмотр + кнопка закрепления) */}
      {isAdmin && activeTab === 'compose' && (
        <div className="card">
          <h2>Фильтры составления списка на обзвон</h2>
          <p className="page-hint">
            Настройте критерии отбора. Список фильтруется по долговой информации (наличие долга),
            минимальному проценту непереданных документов (порог ТЗ 5%) и признаку низкой динамики.
          </p>

          <form onSubmit={handlePreview} style={{ marginTop: '16px' }}>
            <div className="call-list-filters">
              {/* Долговая информация */}
              <div>
                <label className="checkbox-label" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <input
                    type="checkbox"
                    checked={hasDebt}
                    onChange={(e) => setHasDebt(e.target.checked)}
                  />
                  <strong>Только с долгом</strong>
                </label>
                <div style={{ marginTop: '8px' }}>
                  <label>Мин. долг (документов)</label>
                  <input
                    type="number"
                    min="0"
                    className="input"
                    value={minDebt}
                    onChange={(e) => setMinDebt(e.target.value)}
                  />
                </div>
              </div>

              {/* Процент непереданных */}
              <div>
                <label>Мин. % непереданных</label>
                <input
                  type="number"
                  step="0.1"
                  min="0"
                  max="100"
                  className="input"
                  value={minUntransferredPercent}
                  onChange={(e) => setMinUntransferredPercent(e.target.value)}
                  placeholder="5.0"
                />
                <span className="muted" style={{ fontSize: '12px' }}>
                  Порог ТЗ: от 5% (новички с риском включаются всегда)
                </span>
              </div>

              {/* Динамика */}
              <div>
                <label className="checkbox-label" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <input
                    type="checkbox"
                    checked={lowDynamics}
                    onChange={(e) => setLowDynamics(e.target.checked)}
                  />
                  <strong>Динамика низкая</strong>
                </label>
                <div style={{ marginTop: '8px' }}>
                  <label>Категория динамики</label>
                  <select
                    className="input"
                    value={dynamicsCategory}
                    onChange={(e) => setDynamicsCategory(e.target.value)}
                  >
                    <option value="">Любая категория</option>
                    {Object.entries(DYNAMICS_CATEGORY_LABELS).map(([val, lbl]) => (
                      <option key={val} value={val}>
                        {lbl}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {/* Дополнительные фильтры */}
              <div>
                <label>Тип клиента</label>
                <select
                  className="input"
                  value={clientType}
                  onChange={(e) => setClientType(e.target.value)}
                >
                  {CLIENT_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label>Поиск (KCR / ИНН / Организация)</label>
                <input
                  type="text"
                  className="input"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Название или код…"
                />
              </div>
            </div>

            <div style={{ display: 'flex', gap: '12px', marginTop: '16px' }}>
              <button type="submit" className="btn btn-primary" disabled={previewLoading}>
                {previewLoading ? 'Загрузка предпросмотра…' : 'Предпросмотр списка'}
              </button>

              {previewClients && (
                <button
                  type="button"
                  className="btn"
                  style={{ background: '#16a34a', color: '#fff', border: 'none' }}
                  disabled={generating || previewClients.length === 0}
                  onClick={handleGenerate}
                >
                  {generating ? 'Закрепляем…' : `Составить список (${previewClients.length} клиентов)`}
                </button>
              )}
            </div>
          </form>

          {/* Таблица предпросмотра */}
          {previewLoading && <Spinner label="Формирование предпросмотра списка…" />}

          {previewClients && !previewLoading && (
            <div style={{ marginTop: '24px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                <h3>Результаты предпросмотра ({previewClients.length} клиентов)</h3>
                <button
                  type="button"
                  className="btn btn-primary"
                  style={{ background: '#16a34a', border: 'none' }}
                  disabled={generating || previewClients.length === 0}
                  onClick={handleGenerate}
                >
                  {generating ? 'Закрепляем…' : 'Составить и закрепить список для всех операторов'}
                </button>
              </div>

              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Код KCR</th>
                      <th>Организация</th>
                      <th>ИНН</th>
                      <th>Всего док.</th>
                      <th>Не передано (долг)</th>
                      <th>% непереданных</th>
                      <th>Динамика</th>
                      <th>Текущий этап</th>
                      <th>Ответственный</th>
                    </tr>
                  </thead>
                  <tbody>
                    {previewClients.length === 0 ? (
                      <tr>
                        <td colSpan={9} className="table-empty">
                          По выбранным фильтрам клиенты не найдены. Попробуйте ослабить критерии фильтрации.
                        </td>
                      </tr>
                    ) : (
                      previewClients.map((client) => (
                        <tr key={client.clientId}>
                          <td>
                            <Link className="cell-link" to={`/clients/${client.clientId}`}>
                              {client.kcrCode}
                            </Link>
                          </td>
                          <td className="cell-wide">{client.organizationName}</td>
                          <td>{client.inn}</td>
                          <td>{client.totalDocuments ?? '—'}</td>
                          <td>
                            <strong>{client.notTransferredDocuments}</strong>
                          </td>
                          <td>
                            {client.percentNotTransferred != null ? `${client.percentNotTransferred}%` : '—'}
                          </td>
                          <td>
                            <DynamicsBadge category={client.dynamicsCategory} />
                          </td>
                          <td>
                            <span className={`badge badge-stage-${(client.stage ?? '').toLowerCase()}`}>
                              {stageLabel(client.stage)}
                            </span>
                          </td>
                          <td>{client.assigneeUsername ?? <span className="muted">Свободен</span>}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
