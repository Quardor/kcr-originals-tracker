import { useEffect, useState } from 'react';
import {
  exportXlsx,
  fetchAnalyticsDynamics,
  fetchAnalyticsOperators,
  fetchAnalyticsSummary,
  fetchAnalyticsTopDebt,
} from '../api/ai';
import { errorMessage } from '../api/errors';
import { ErrorBox, Spinner } from '../components/ui';
import { DYNAMICS_CATEGORY_LABELS, dynamicsCategoryLabel, stageLabel } from '../labels';

/**
 * UC-4: аналитика для дашбордов — агрегаты считает kcr-ml из данных бэкенда.
 * Все числа приходят готовыми сериями; экспорт — теми же видами отчётов (UC-5).
 */

export default function AiAnalyticsPage() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [exporting, setExporting] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([fetchAnalyticsSummary(), fetchAnalyticsDynamics(), fetchAnalyticsTopDebt(10), fetchAnalyticsOperators()])
      .then(([summary, dynamics, topDebt, operators]) => {
        if (!cancelled) setData({ summary, dynamics, topDebt, operators });
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
  }, []);

  const download = async (kind, title) => {
    setExporting(kind);
    setError(null);
    try {
      await exportXlsx({ kind }, `${kind}.xlsx`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setExporting(null);
    }
  };

  if (loading) return <Spinner label="Считаю агрегаты…" />;
  if (error && !data) return <ErrorBox message={error} />;
  if (!data) return null;

  const totals = data.summary.totals;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Аналитика</h1>
          <p className="page-hint">Агрегаты по данным сервиса на момент загрузки страницы.</p>
        </div>
      </div>

      <ErrorBox message={error} />

      <div className="ai-stat-grid">
        <StatCard label="Клиентов" value={totals.clients} />
        <StatCard label="С непереданными" value={totals.withDebt} />
        <StatCard label="Непередано документов" value={totals.notTransferredDocuments} />
        <StatCard label="К контакту сегодня" value={data.operators.totals.dueToday} />
      </div>

      <div className="card">
        <div className="ai-export-row">
          <h2>Категории динамики</h2>
          <button type="button" className="btn" onClick={() => download('analytics_summary')} disabled={exporting !== null}>
            {exporting === 'analytics_summary' ? 'Готовлю…' : 'Экспорт'}
          </button>
        </div>
        <div className="ai-bar-list">
          {Object.entries(data.summary.byDynamicsCategory).map(([category, count]) => (
            <BarRow key={category} label={dynamicsCategoryLabel(category)} value={count} max={totals.clients} />
          ))}
        </div>
        <h2 style={{ marginTop: 16 }}>Этапы работы</h2>
        <div className="ai-bar-list">
          {Object.entries(data.summary.byStage).map(([stage, count]) => (
            <BarRow key={stage} label={stageLabel(stage)} value={count} max={totals.clients} />
          ))}
        </div>
      </div>

      <div className="card">
        <div className="ai-export-row">
          <h2>Динамика по периодам</h2>
          <button type="button" className="btn" onClick={() => download('analytics_dynamics')} disabled={exporting !== null}>
            {exporting === 'analytics_dynamics' ? 'Готовлю…' : 'Экспорт'}
          </button>
        </div>
        {data.dynamics.series.length === 0 ? (
          <p className="muted">Срезов пока нет.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Период</th>
                  {Object.keys(DYNAMICS_CATEGORY_LABELS).map((key) => (
                    <th key={key} title={DYNAMICS_CATEGORY_LABELS[key]}>{shortCategory(key)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.dynamics.series.map((row) => (
                  <tr key={row.period}>
                    <td>{row.period}</td>
                    {Object.keys(DYNAMICS_CATEGORY_LABELS).map((key) => <td key={key}>{row[key] ?? 0}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="ai-export-row">
          <h2>Топ по доле непереданных</h2>
          <button type="button" className="btn" onClick={() => download('analytics_top_debt')} disabled={exporting !== null}>
            {exporting === 'analytics_top_debt' ? 'Готовлю…' : 'Экспорт'}
          </button>
        </div>
        {data.topDebt.items.length === 0 ? (
          <p className="muted">Клиентов с долгом нет.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Код KCR</th>
                  <th>Организация</th>
                  <th>Ответственный</th>
                  <th>Период</th>
                  <th>Непередано</th>
                  <th>%</th>
                </tr>
              </thead>
              <tbody>
                {data.topDebt.items.map((item) => (
                  <tr key={item.kcrCode}>
                    <td><span className="badge badge-code">{item.kcrCode}</span></td>
                    <td>{item.organizationName}</td>
                    <td>{item.assigneeUsername ?? '—'}</td>
                    <td>{item.reportingPeriod}</td>
                    <td>{item.notTransferredDocuments} из {item.totalDocuments ?? '—'}</td>
                    <td><span className="ai-score">{item.percentNotTransferred}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="ai-export-row">
          <h2>Нагрузка операторов</h2>
          <button type="button" className="btn" onClick={() => download('analytics_operators')} disabled={exporting !== null}>
            {exporting === 'analytics_operators' ? 'Готовлю…' : 'Экспорт'}
          </button>
        </div>
        {data.operators.operators.length === 0 ? (
          <p className="muted">Клиенты ещё не взяты в работу.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Оператор</th>
                  <th>В работе</th>
                  <th>К контакту сегодня</th>
                </tr>
              </thead>
              <tbody>
                {data.operators.operators.map((operator) => (
                  <tr key={operator.username}>
                    <td>{operator.username}</td>
                    <td>{operator.inWork}</td>
                    <td>{operator.dueToday}</td>
                  </tr>
                ))}
                <tr>
                  <td className="muted">— без ответственного (пул)</td>
                  <td className="muted">{data.operators.totals.unassignedInPool}</td>
                  <td className="muted">{data.operators.totals.dueTodayUnassigned}</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({ label, value }) {
  return (
    <div className="card ai-stat-card">
      <h4>{label}</h4>
      <div className="ai-stat-value">{value}</div>
    </div>
  );
}

function BarRow({ label, value, max }) {
  const width = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div className="ai-bar-row">
      <span className="ai-bar-label">{label}</span>
      <div className="ai-bar-track"><div className="ai-bar-fill" style={{ width: `${width}%` }} /></div>
      <span className="ai-bar-value">{value}</span>
    </div>
  );
}

function shortCategory(key) {
  const map = {
    NO_DEBT: 'Передано',
    FIRST_OCCURRENCE: 'Впервые',
    DECREASING: 'Снижается',
    UNCHANGED_OR_GROWING: 'Стагнация',
    REAPPEARED: 'Вернулся',
    PERSISTENT: 'Затяжной',
    NEWCOMER_RISK: 'Новичок',
  };
  return map[key] ?? key;
}
