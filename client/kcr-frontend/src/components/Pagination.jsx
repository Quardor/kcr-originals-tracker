export default function Pagination({ page, size, totalElements, totalPages, onPage, onSize }) {
  const from = totalElements === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, totalElements);

  return (
    <div className="pagination">
      <span className="pagination-info">
        {totalElements === 0
          ? 'Ничего не найдено'
          : `Показаны ${from}–${to} из ${totalElements}`}
      </span>
      <div className="pagination-controls">
        <label className="pagination-size">
          Строк:{' '}
          <select value={size} onChange={(e) => onSize(Number(e.target.value))}>
            {[20, 50, 100].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="btn" disabled={page === 0} onClick={() => onPage(page - 1)}>
          ← Назад
        </button>
        <span className="pagination-page">
          Стр. {totalPages === 0 ? 0 : page + 1} из {totalPages}
        </span>
        <button
          type="button"
          className="btn"
          disabled={page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
        >
          Вперёд →
        </button>
      </div>
    </div>
  );
}
