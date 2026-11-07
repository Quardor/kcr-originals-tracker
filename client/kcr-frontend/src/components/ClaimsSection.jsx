import { useCallback, useEffect, useState } from 'react';
import { addSanction, createClaim, fetchClaims, updateClaim, updateSanction } from '../api/claims';
import { errorMessage } from '../api/errors';
import { ErrorBox, Spinner } from './ui';
import { CLAIM_STATUSES, SANCTION_STATUSES, claimStatusLabel, sanctionStatusLabel } from '../labels';

/**
 * Претензии и санкции клиента (§11.4): чтение — обе роли, мутации — админ.
 * `version` инкрементируется родителем для перечитывания списка.
 */
export default function ClaimsSection({ clientId, isAdmin, version = 0 }) {
  const [claims, setClaims] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const [number, setNumber] = useState('');
  const [claimDate, setClaimDate] = useState('');
  const [incidentNumber, setIncidentNumber] = useState('');
  const [comment, setComment] = useState('');

  const [sanctionNames, setSanctionNames] = useState({});
  const [claimDrafts, setClaimDrafts] = useState({});

  const load = useCallback(() => fetchClaims(clientId), [clientId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    load()
      .then((result) => {
        if (!cancelled) setClaims(result);
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

  const draftFor = (claimId) =>
    claimDrafts[claimId] ?? { status: '', receivedByClientAt: '' };

  const setDraft = (claimId, patch) => {
    setClaimDrafts((prev) => ({
      ...prev,
      [claimId]: { ...draftFor(claimId), ...patch },
    }));
  };

  const run = async (action, ...args) => {
    setBusy(true);
    setError(null);
    try {
      await action(...args);
      setClaims(await load());
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitClaim = (event) => {
    event.preventDefault();
    if (!number.trim() || !claimDate) {
      setError('Укажите номер и дату претензии');
      return;
    }
    const body = { number: number.trim(), claimDate };
    if (incidentNumber.trim()) body.incidentNumber = incidentNumber.trim();
    if (comment.trim()) body.comment = comment.trim();
    run(async () => {
      await createClaim(clientId, body);
      setNumber('');
      setClaimDate('');
      setIncidentNumber('');
      setComment('');
    });
  };

  const submitSanction = (event, claimId) => {
    event.preventDefault();
    const name = (sanctionNames[claimId] ?? '').trim();
    if (!name) {
      setError('Укажите название санкции');
      return;
    }
    run(async () => {
      await addSanction(claimId, { name });
      setSanctionNames((prev) => ({ ...prev, [claimId]: '' }));
    });
  };

  if (loading && !claims) return <Spinner />;

  return (
    <div className="claims-section">
      <ErrorBox message={error} />

      {isAdmin && (
        <form className="claim-form" onSubmit={submitClaim}>
          <div className="form-grid">
            <label className="field">
              <span>Номер претензии</span>
              <input value={number} onChange={(e) => setNumber(e.target.value)} placeholder="ПР-123" />
            </label>
            <label className="field">
              <span>Дата</span>
              <input type="date" value={claimDate} onChange={(e) => setClaimDate(e.target.value)} />
            </label>
            <label className="field">
              <span>Номер инцидента</span>
              <input value={incidentNumber} onChange={(e) => setIncidentNumber(e.target.value)} placeholder="INC-77" />
            </label>
            <label className="field field-wide">
              <span>Комментарий</span>
              <input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Основание" />
            </label>
          </div>
          <button type="submit" className="btn" disabled={busy}>+ Претензия</button>
        </form>
      )}

      {claims?.length === 0 && <p className="table-empty">Претензий нет</p>}

      <ul className="claim-list">
        {claims?.map((claim) => {
          const draft = draftFor(claim.id);
          return (
            <li key={claim.id} className="claim-item">
              <div className="claim-head">
                <strong>№ {claim.number}</strong>
                <span className={`badge badge-claim-${(claim.status ?? '').toLowerCase()}`}>
                  {claimStatusLabel(claim.status)}
                </span>
                <span className="event-meta">
                  от {claim.claimDate}
                  {claim.incidentNumber ? ` · инцидент ${claim.incidentNumber}` : ''}
                  {claim.receivedByClientAt ? ` · получена клиентом ${claim.receivedByClientAt}` : ''}
                </span>
              </div>
              {claim.comment && <p className="event-comment">{claim.comment}</p>}

              {isAdmin && (
                <div className="claim-controls">
                  <select
                    value={draft.status}
                    onChange={(e) => setDraft(claim.id, { status: e.target.value })}
                  >
                    <option value="">— статус —</option>
                    {CLAIM_STATUSES.map((s) => (
                      <option key={s.value} value={s.value}>{s.label}</option>
                    ))}
                  </select>
                  <input
                    type="date"
                    title="Дата получения клиентом"
                    value={draft.receivedByClientAt}
                    onChange={(e) => setDraft(claim.id, { receivedByClientAt: e.target.value })}
                  />
                  <button
                    type="button"
                    className="btn"
                    disabled={busy || (!draft.status && !draft.receivedByClientAt)}
                    onClick={() =>
                      run(() => updateClaim(claim.id, {
                        ...(draft.status ? { status: draft.status } : {}),
                        ...(draft.receivedByClientAt ? { receivedByClientAt: draft.receivedByClientAt } : {}),
                      }))
                    }
                  >
                    Сохранить
                  </button>
                </div>
              )}

              <ul className="sanction-list">
                {claim.sanctions?.map((sanction) => (
                  <li key={sanction.id} className="sanction-item">
                    <span>{sanction.name}</span>
                    <span className={`badge badge-sanction-${(sanction.status ?? '').toLowerCase()}`}>
                      {sanctionStatusLabel(sanction.status)}
                    </span>
                    {isAdmin && (
                      <select
                        value=""
                        onChange={(e) =>
                          e.target.value && run(() => updateSanction(sanction.id, { status: e.target.value }))
                        }
                      >
                        <option value="">— сменить статус —</option>
                        {SANCTION_STATUSES.map((s) => (
                          <option key={s.value} value={s.value}>{s.label}</option>
                        ))}
                      </select>
                    )}
                  </li>
                ))}
              </ul>

              {isAdmin && (
                <form className="sanction-form" onSubmit={(e) => submitSanction(e, claim.id)}>
                  <input
                    placeholder="Название санкции"
                    value={sanctionNames[claim.id] ?? ''}
                    onChange={(e) =>
                      setSanctionNames((prev) => ({ ...prev, [claim.id]: e.target.value }))
                    }
                  />
                  <button type="submit" className="btn" disabled={busy}>+ Санкция</button>
                </form>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
