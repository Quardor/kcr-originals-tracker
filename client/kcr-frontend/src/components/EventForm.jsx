import { useState } from 'react';
import { createEvent } from '../api/events';
import { errorMessage } from '../api/errors';
import { ErrorBox } from './ui';
import { CALL_RESULTS, MANUAL_EVENT_TYPES, WORK_STAGES, WORK_STAGES_OPERATOR } from '../labels';

/**
 * Форма ручного события истории (§11.4): звонок / письмо / комментарий.
 * Поля результата, договорённости, сроков и ссылки на запись доступны только
 * для звонка; звонок может также сменить этап (сервер валидирует правила).
 *
 * @param {object} props
 * @param {string|number} props.clientId
 * @param {object|null} props.client карточка (текущий этап/ответственный)
 * @param {boolean} props.isAdmin админ может ставить любой этап
 * @param {number|null} props.currentUserId id текущего пользователя из JWT
 * @param {function} props.onCreated вызывается после успешного создания
 */
export default function EventForm({ clientId, client, isAdmin, currentUserId, onCreated }) {
  const [type, setType] = useState('CALL');
  const [callResult, setCallResult] = useState('CONNECTED');
  const [comment, setComment] = useState('');
  const [agreement, setAgreement] = useState('');
  const [clientDeadline, setClientDeadline] = useState('');
  const [nextContactAt, setNextContactAt] = useState('');
  const [recordUrl, setRecordUrl] = useState('');
  const [stage, setStage] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const isCall = type === 'CALL';
  const ownClient =
    client != null && client.assigneeId != null && client.assigneeId === currentUserId;
  const stageOptions = isAdmin ? WORK_STAGES : ownClient ? WORK_STAGES_OPERATOR : [];
  const canPickStage = isCall && stageOptions.length > 0;

  const switchType = (nextType) => {
    setType(nextType);
    setAgreement('');
    setClientDeadline('');
    setNextContactAt('');
    setRecordUrl('');
    setStage('');
    setError(null);
  };

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = { type };
      if (comment.trim()) body.comment = comment.trim();
      if (isCall) {
        body.callResult = callResult;
        if (agreement.trim()) body.agreement = agreement.trim();
        if (clientDeadline) body.clientDeadline = clientDeadline;
        if (nextContactAt) body.nextContactAt = nextContactAt;
        if (recordUrl.trim()) body.recordUrl = recordUrl.trim();
        if (stage) body.stage = stage;
      }
      await createEvent(clientId, body);
      setComment('');
      setAgreement('');
      setClientDeadline('');
      setNextContactAt('');
      setRecordUrl('');
      setStage('');
      onCreated();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="event-form" onSubmit={submit}>
      <ErrorBox message={error} />
      <div className="form-grid">
        <label className="field">
          <span>Тип события</span>
          <select value={type} onChange={(e) => switchType(e.target.value)}>
            {MANUAL_EVENT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </label>

        {isCall && (
          <label className="field">
            <span>Результат звонка</span>
            <select value={callResult} onChange={(e) => setCallResult(e.target.value)}>
              {CALL_RESULTS.map((r) => (
                <option key={r.value} value={r.value}>{r.label}</option>
              ))}
            </select>
          </label>
        )}

        {isCall && (
          <label className="field">
            <span>Срок со стороны клиента</span>
            <input type="date" value={clientDeadline} onChange={(e) => setClientDeadline(e.target.value)} />
          </label>
        )}

        {isCall && (
          <label className="field">
            <span>Дата следующего контакта</span>
            <input type="date" value={nextContactAt} onChange={(e) => setNextContactAt(e.target.value)} />
          </label>
        )}

        {canPickStage && (
          <label className="field">
            <span>Сменить этап</span>
            <select value={stage} onChange={(e) => setStage(e.target.value)}>
              <option value="">— без изменения —</option>
              {stageOptions.map((s) => (
                <option key={s.value} value={s.value}>{s.label}</option>
              ))}
            </select>
          </label>
        )}

        {isCall && (
          <label className="field field-wide">
            <span>Ссылка на запись разговора</span>
            <input
              type="url"
              placeholder="https://…"
              value={recordUrl}
              onChange={(e) => setRecordUrl(e.target.value)}
            />
          </label>
        )}

        {isCall && (
          <label className="field field-wide">
            <span>Договорённость</span>
            <textarea
              rows={2}
              placeholder="Что обещал клиент"
              value={agreement}
              onChange={(e) => setAgreement(e.target.value)}
            />
          </label>
        )}

        <label className="field field-wide">
          <span>Комментарий</span>
          <textarea
            rows={2}
            placeholder={isCall ? 'Детали разговора' : 'Текст письма или комментарий'}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </label>
      </div>

      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? 'Сохранение…' : 'Добавить событие'}
      </button>
    </form>
  );
}
