import { useState } from 'react';
import { fetchBrief } from '../api/ai';
import { errorMessage } from '../api/errors';
import { ErrorBox, Spinner } from './ui';
import { dynamicsCategoryLabel, eventTypeLabel, formatDateTime, stageLabel } from '../labels';

/**
 * UC-2: сводка перед звонком — детерминированные факты (срезы, динамика, события,
 * претензии) + текстовое пояснение от LLM. Кнопка раскрывает панель со сводкой.
 */
export default function BriefSection({ clientId }) {
  const [brief, setBrief] = useState(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const toggle = async () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (brief || loading) return;
    setLoading(true);
    setError(null);
    try {
      setBrief(await fetchBrief(clientId));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card">
      <div className="ai-export-row">
        <h2>Сводка перед звонком</h2>
        <button type="button" className="btn" onClick={toggle}>
          {open ? 'Скрыть' : 'Сформировать'}
        </button>
      </div>

      {!open && (
        <p className="page-hint">
          ИИ-помощник соберёт состояние документов, динамику, последние контакты и
          договорённости в краткий текст перед звонком.
        </p>
      )}

      {open && (
        <>
          <ErrorBox message={error} />
          {loading && <Spinner label="Собираю сводку…" />}
          {brief && !loading && (
            <div className="ai-brief">
              <p className="ai-brief-summary">{brief.summary || 'Помощник не вернул текст сводки.'}</p>

              <dl className="details-grid">
                <div className="detail-field">
                  <dt>Последний срез</dt>
                  <dd>
                    {brief.documents
                      ? `${brief.documents.reportingPeriod}: не передано ${brief.documents.notTransferredDocuments}`
                        + ` из ${brief.documents.totalDocuments ?? '—'}`
                        + (brief.documents.percentNotTransferred != null ? ` (${brief.documents.percentNotTransferred}%)` : '')
                      : 'данных о срезах нет'}
                  </dd>
                </div>
                <div className="detail-field">
                  <dt>Динамика</dt>
                  <dd>{brief.dynamics ? dynamicsCategoryLabel(brief.dynamics.category) : '—'}</dd>
                </div>
                <div className="detail-field">
                  <dt>Этап</dt>
                  <dd>{stageLabel(brief.client.stage)}</dd>
                </div>
                <div className="detail-field">
                  <dt>Следующий контакт</dt>
                  <dd>{brief.nextActionDate ?? 'не назначен'}</dd>
                </div>
              </dl>

              {brief.dynamicsHistory?.length > 0 && (
                <div className="chips">
                  {brief.dynamicsHistory.map((period) => (
                    <span key={period.period} className="chip chip-static">
                      {period.period}: {dynamicsCategoryLabel(period.category)}
                    </span>
                  ))}
                </div>
              )}

              {brief.recentEvents?.length > 0 && (
                <>
                  <h4>Последние события</h4>
                  <ul className="ai-brief-events">
                    {brief.recentEvents.slice(0, 5).map((event) => (
                      <li key={event.id}>
                        {formatDateTime(event.createdAt)} — {eventTypeLabel(event.type)}
                        {event.agreement ? `, договорённость: ${event.agreement}` : ''}
                        {event.comment ? `, ${event.comment}` : ''}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
