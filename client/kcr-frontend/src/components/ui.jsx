export function Spinner({ label = 'Загрузка…' }) {
  return (
    <div className="spinner-wrap" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function ErrorBox({ message }) {
  if (!message) return null;
  return (
    <div className="error-box" role="alert">
      {message}
    </div>
  );
}

const CLIENT_TYPE_LABELS = {
  VIP: 'VIP',
  TECH_PARTNER: 'Технологический партнёр',
  WORKS_VIA_MP: 'Работает через МП',
  NEWCOMER: 'Новичок',
};

export function ClientTypeBadge({ type }) {
  if (!type) return <span className="muted">—</span>;
  return <span className={`badge badge-type-${type.toLowerCase().replace(/_/g, '-')}`}>{CLIENT_TYPE_LABELS[type] ?? type}</span>;
}

/** Триады булевых полей клиента: null = «нет данных в выгрузке». */
export function TriStateBadge({ value, positive, negative }) {
  if (value === null || value === undefined) return <span className="muted">нет данных</span>;
  return <span className={`badge ${value ? 'badge-on' : 'badge-off'}`}>{value ? positive : negative}</span>;
}
