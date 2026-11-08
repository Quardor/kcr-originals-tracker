import { useState } from 'react';
import { exportXlsx, prioritize } from '../api/ai';
import { errorMessage } from '../api/errors';
import { ErrorBox, Spinner } from '../components/ui';
import { dynamicsCategoryLabel } from '../labels';

/**
 * UC-3: приоритетная выборка (админ). Свободный запрос → структурированные критерии
 * + детерминированный список с баллами и обоснованием. Список — предложение:
 * включение в работу остаётся решением администратора в карточке клиента.
 */

const CRITERIA_CHIPS = [
  { key: 'minUntransferredPercent', label: (value) => `непередано ≥ ${value}%` },
  { key: 'maxUntransferredPercent', label: (value) => `непередано ≤ ${value}%` },
  { key: 'inWork', label: (value) => (value ? 'в работе' : 'не в работе') },
  { key: 'assigned', label: (value) => (value ? 'есть ответственный' : 'без ответственного') },
  { key: 'nextContactOverdue', label: () => 'контакт просрочен' },
  { key: 'hasDebt', label: (value) => (value ? 'есть долг' : 'без долга') },
];

export default function AiPrioritizePage() {
  const [prompt, setPrompt] = useState('');
  const [limit, setLimit] = useState(20);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  const run = async (event) => {
    event.preventDefault();
    if (!prompt.trim() || loading) return;
    setLoading(true);
    setError(null);
    try {
      setResult(await prioritize({ prompt: prompt.trim(), limit }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const criteriaChips = () => {
    if (!result?.criteria) return [];
    const chips = [];
    const criteria = result.criteria;
    for (const chip of CRITERIA_CHIPS) {
      const value = criteria[chip.key];
      if (value !== null && value !== undefined && value !== false) chips.push(chip.label(value));
    }
    for (const type of criteria.clientTypes ?? []) chips.push(`тип: ${type}`);
    for (const type of criteria.excludeClientTypes ?? []) chips.push(`исключить: ${type}`);
    for (const category of criteria.dynamicsCategories ?? []) chips.push(`динамика: ${dynamicsCategoryLabel(category)}`);
    return chips;
  };

  const download = async () => {
    if (!result) return;
    setExporting(true);
    try {
      await exportXlsx(
        {
          kind: 'table',
          table: {
            title: 'Приоритетная выборка',
            columns: ['Код KCR', 'Организация', 'Балл', 'Непередано, %', 'Обоснование'],
            rows: result.items.map((item) => [
              item.kcrCode,
              item.organizationName,
              item.score,
              item.untransferredPercent ?? '',
              (item.reasons ?? []).join('; '),
            ]),
          },
        },
        'prioritization.xlsx',
      );
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Приоритизация клиентов</h1>
          <p className="page-hint">
            Опишите выборку словами — помощник превратит её в критерии и оценит приоритет
            каждого клиента. Список — предложение, решение о включении в работу за вами.
          </p>
        </div>
      </div>

      <form className="card ai-prioritize-form" onSubmit={run}>
        <textarea
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="Например: не VIP, непередано больше 25% документов, ещё не в работе — сначала самые запущенные"
          rows={3}
        />
        <div className="ai-prioritize-controls">
          <label className="ai-limit">
            Показать:
            <select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
            </select>
          </label>
          <button type="submit" className="btn btn-primary" disabled={loading || !prompt.trim()}>
            Подобрать
          </button>
        </div>
      </form>

      <ErrorBox message={error} />
      {loading && <Spinner label="Формирую выборку…" />}

      {result && !loading && (
        <>
          <div className="card">
            <h2>Критерии выборки</h2>
            <div className="chips">
              {criteriaChips().map((chip) => <span key={chip} className="chip chip-static">{chip}</span>)}
              {criteriaChips().length === 0 && <span className="muted">без ограничений</span>}
            </div>
            {result.explanation && <p className="ai-explanation">{result.explanation}</p>}
          </div>

          <div className="card">
            <div className="ai-export-row">
              <h2>Результат ({result.items.length})</h2>
              <button type="button" className="btn" onClick={download} disabled={exporting || result.items.length === 0}>
                Экспорт в xlsx
              </button>
            </div>
            {result.items.length === 0 ? (
              <p className="muted">Подходящих клиентов не найдено.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Код KCR</th>
                      <th>Организация</th>
                      <th>Балл</th>
                      <th>Непередано, %</th>
                      <th>Обоснование</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.items.map((item, index) => (
                      <tr key={item.kcrCode}>
                        <td>{index + 1}</td>
                        <td><span className="badge badge-code">{item.kcrCode}</span></td>
                        <td>{item.organizationName}</td>
                        <td><span className="ai-score">{item.score}</span></td>
                        <td>{item.untransferredPercent ?? '—'}</td>
                        <td className="ai-reasons">{(item.reasons ?? []).join('; ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
