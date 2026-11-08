import { useCallback, useEffect, useState } from 'react';
import { fetchEvents } from '../api/events';
import { errorMessage } from '../api/errors';
import Pagination from './Pagination';
import { ErrorBox, Spinner } from './ui';
import { callResultLabel, eventTypeLabel, formatDateTime } from '../labels';

/**
 * Единая история клиента (§11.2): хронологически от новых событий к старым,
 * с датой, временем и автором. `version` инкрементируется родителем, чтобы
 * перечитать ленту после новой записи.
 */
export default function HistoryFeed({ clientId, version = 0 }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(() => fetchEvents(clientId, { page, size }), [clientId, page, size]);

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
  }, [load, version]);

  return (
    <div className="history-feed">
      <ErrorBox message={error} />
      {loading && !data ? (
        <Spinner />
      ) : (
        <>
          {data?.content?.length === 0 && (
            <p className="table-empty">История пока пуста</p>
          )}
          <ul className="event-list">
            {data?.content?.map((event) => (
              <li key={event.id} className={`event-item event-${(event.type ?? '').toLowerCase()}`}>
                <div className="event-head">
                  <span className={`badge badge-event-${(event.type ?? '').toLowerCase()}`}>
                    {eventTypeLabel(event.type)}
                  </span>
                  <span className="event-meta">
                    {formatDateTime(event.createdAt)} · {event.authorUsername ?? '—'}
                  </span>
                </div>
                {event.comment && <p className="event-comment">{event.comment}</p>}
                {event.type === 'CALL' && (
                  <dl className="event-details">
                    <div><dt>Результат</dt><dd>{callResultLabel(event.callResult)}</dd></div>
                    {event.agreement && <div><dt>Договорённость</dt><dd>{event.agreement}</dd></div>}
                    <div><dt>Срок клиента</dt><dd>{event.clientDeadline ?? '—'}</dd></div>
                    <div><dt>Следующий контакт</dt><dd>{event.nextContactAt ?? '—'}</dd></div>
                    {event.recordUrl && (
                      <div>
                        <dt>Запись разговора</dt>
                        <dd>
                          <a href={event.recordUrl} target="_blank" rel="noreferrer">прослушать</a>
                        </dd>
                      </div>
                    )}
                  </dl>
                )}
              </li>
            ))}
          </ul>

          {data && data.totalPages > 1 && (
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
    </div>
  );
}
