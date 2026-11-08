import { useState } from 'react';
import {
  applyClientsImport,
  applySnapshotsImport,
  previewClientsImport,
  previewSnapshotsImport,
} from '../api/imports';
import { errorMessage } from '../api/errors';
import { ErrorBox } from '../components/ui';

const MAX_FILE_SIZE = 10 * 1024 * 1024; // лимит бэкенда — 10MB

function IssuesTable({ issues }) {
  if (!issues?.length) return null;
  return (
    <div className="table-wrap">
      <table className="table table-issues">
        <thead>
          <tr>
            <th>Лист</th>
            <th>Строка</th>
            <th>Колонка</th>
            <th>Проблема</th>
          </tr>
        </thead>
        <tbody>
          {issues.map((issue, index) => (
            <tr key={index}>
              <td>{issue.sheet ?? '—'}</td>
              <td>{issue.row ?? '—'}</td>
              <td>{issue.column ?? '—'}</td>
              <td>{issue.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CodesList({ title, codes }) {
  const [expanded, setExpanded] = useState(false);
  if (!codes?.length) return null;
  const shown = expanded ? codes : codes.slice(0, 20);
  return (
    <div className="codes-list">
      <h4>
        {title} — {codes.length}
      </h4>
      <span className="codes-items">
        {shown.map((code) => (
          <span key={code} className="badge badge-code">{code}</span>
        ))}
        {codes.length > 20 && (
          <button type="button" className="link-button" onClick={() => setExpanded((v) => !v)}>
            {expanded ? 'свернуть' : `показать все ${codes.length}`}
          </button>
        )}
      </span>
    </div>
  );
}

function FilePicker({ file, onFile }) {
  return (
    <input
      type="file"
      accept=".xlsx,.xls"
      onChange={(e) => onFile(e.target.files?.[0] ?? null)}
    />
  );
}

/**
 * Общая логика секции импорта: выбранный файл → предпросмотр (без записи) → применение.
 * Смена файла или параметров сбрасывает результаты — применять можно только просмотренный файл.
 */
function ImportSection({
  title,
  description,
  withDate = false,
  withPeriod = false,
  onPreview,
  onApply,
  renderPreview,
  renderReport,
}) {
  const [file, setFile] = useState(null);
  const [snapshotDate, setSnapshotDate] = useState('');
  const [reportingPeriod, setReportingPeriod] = useState('');
  const [preview, setPreview] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);

  const resetResults = () => {
    setPreview(null);
    setReport(null);
  };

  const changeFile = (nextFile) => {
    setFile(nextFile);
    resetResults();
    setError(nextFile && nextFile.size > MAX_FILE_SIZE ? 'Файл больше 10 МБ' : null);
  };

  const run = async (kind) => {
    setError(null);
    if (!file) {
      setError('Выберите файл');
      return;
    }
    if (file.size > MAX_FILE_SIZE) {
      setError('Файл больше 10 МБ');
      return;
    }
    if (withDate && !snapshotDate) {
      setError('Укажите дату среза');
      return;
    }
    if (withPeriod && !reportingPeriod.trim()) {
      setError('Укажите отчётный период (как называется лист в файле, например «1 кв 2026»)');
      return;
    }
    setBusy(kind);
    try {
      if (kind === 'preview') {
        setPreview(await onPreview(file, snapshotDate, reportingPeriod.trim()));
        setReport(null);
      } else {
        setReport(await onApply(file, snapshotDate, reportingPeriod.trim()));
        setPreview(null);
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card">
      <h2>{title}</h2>
      <p className="hint">{description}</p>

      <div className="import-controls">
        <FilePicker file={file} onFile={changeFile} />
        {withPeriod && (
          <label className="field field-inline">
            <span>Отчётный период</span>
            <input
              type="text"
              value={reportingPeriod}
              placeholder="1 кв 2026"
              onChange={(e) => {
                setReportingPeriod(e.target.value);
                resetResults();
              }}
            />
          </label>
        )}
        {withDate && (
          <label className="field field-inline">
            <span>Дата среза</span>
            <input
              type="date"
              value={snapshotDate}
              onChange={(e) => {
                setSnapshotDate(e.target.value);
                resetResults();
              }}
            />
          </label>
        )}
        <button type="button" className="btn" disabled={!file || busy !== null} onClick={() => run('preview')}>
          {busy === 'preview' ? 'Проверка…' : 'Предпросмотр'}
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={!file || !preview || busy !== null}
          onClick={() => run('apply')}
          title={preview ? 'Применить просмотренный файл' : 'Сначала выполните предпросмотр'}
        >
          {busy === 'apply' ? 'Применение…' : 'Применить'}
        </button>
      </div>

      <ErrorBox message={error} />

      {preview && renderPreview(preview)}
      {report && renderReport(report)}
    </section>
  );
}

export default function ImportPage() {
  return (
    <>
      <div className="page-head">
        <h1>Импорт из Excel</h1>
      </div>
      <p className="hint page-hint">
        Импорт не удаляет отсутствующих в файле клиентов; срезы добавляются новыми строками
        и никогда не перезаписываются. Сначала предпросмотр — запись только после проверки.
      </p>

      <ImportSection
        title="База клиентов"
        description="Выгрузка клиентов: обновление существующих и создание новых по коду KCR."
        onPreview={previewClientsImport}
        onApply={applyClientsImport}
        renderPreview={(p) => (
          <div className="import-result">
            <h3>Предпросмотр</h3>
            <p>
              Будет создано: <strong>{p.toCreate.length}</strong>, обновлено:{' '}
              <strong>{p.toUpdate.length}</strong>
              {p.issues.length > 0 && (
                <>
                  , проблемных строк: <strong>{p.issues.length}</strong> (в импорт не попадут)
                </>
              )}
            </p>
            <CodesList title="Новые" codes={p.toCreate.map((r) => r.kcrCode)} />
            <CodesList title="Обновляемые" codes={p.toUpdate.map((r) => r.kcrCode)} />
            <IssuesTable issues={p.issues} />
          </div>
        )}
        renderReport={(r) => (
          <div className="import-result">
            <h3>Готово</h3>
            <p>
              Создано: <strong>{r.created}</strong>, обновлено: <strong>{r.updated}</strong>
              {r.issues.length > 0 && (
                <>
                  , пропущено строк с ошибками: <strong>{r.issues.length}</strong>
                </>
              )}
            </p>
            <IssuesTable issues={r.issues} />
          </div>
        )}
      />

      <ImportSection
        title="Срезы передачи документов"
        description="Выберите отчётный период и дату среза: импортируются листы файла, чьё имя совпадает с периодом; остальные листы пропускаются."
        withDate
        withPeriod
        onPreview={(file, date, period) => previewSnapshotsImport(file, date, period)}
        onApply={(file, date, period) => applySnapshotsImport(file, date, period)}
        renderPreview={(p) => (
          <div className="import-result">
            <h3>Предпросмотр</h3>
            <p>
              Срезов к записи: <strong>{p.rows.length}</strong>
              {p.skippedSheets.length > 0 && (
                <>
                  , пропущено листов других периодов: <strong>{p.skippedSheets.length}</strong>
                </>
              )}
              {p.unmatchedKcrCodes.length > 0 && (
                <>
                  , кодов без клиента в базе: <strong>{p.unmatchedKcrCodes.length}</strong>
                </>
              )}
              {p.issues.length > 0 && <> , проблемных строк: <strong>{p.issues.length}</strong></>}
            </p>
            {p.rows.length > 0 && (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Код KCR</th>
                      <th>Период</th>
                      <th>Всего (файл)</th>
                      <th>Всего (итог)</th>
                      <th>Не передано</th>
                    </tr>
                  </thead>
                  <tbody>
                    {p.rows.map((row, index) => (
                      <tr key={`${row.kcrCode}-${row.reportingPeriod}-${index}`}>
                        <td>{row.kcrCode}</td>
                        <td>{row.reportingPeriod}</td>
                        <td>{row.totalInFile ?? '—'}</td>
                        <td>
                          {row.totalResolved ?? '—'}
                          {row.totalInFile == null && row.totalResolved != null && (
                            <span className="muted"> (наследовано)</span>
                          )}
                        </td>
                        <td>{row.notTransferredDocuments}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <CodesList title="Пропущены листы других периодов" codes={p.skippedSheets} />
            <CodesList title="Коды без клиента" codes={p.unmatchedKcrCodes} />
            <IssuesTable issues={p.issues} />
          </div>
        )}
        renderReport={(r) => (
          <div className="import-result">
            <h3>Готово</h3>
            <p>
              Записано срезов: <strong>{r.created}</strong>
              {r.skippedSheets.length > 0 && (
                <>
                  , пропущено листов других периодов: <strong>{r.skippedSheets.length}</strong>
                </>
              )}
              {r.unmatchedKcrCodes.length > 0 && (
                <>
                  , кодов без клиента (пропущено): <strong>{r.unmatchedKcrCodes.length}</strong>
                </>
              )}
              {r.issues.length > 0 && <> , строк с ошибками: <strong>{r.issues.length}</strong></>}
            </p>
            <CodesList title="Пропущены листы других периодов" codes={r.skippedSheets} />
            <CodesList title="Коды без клиента" codes={r.unmatchedKcrCodes} />
            <IssuesTable issues={r.issues} />
          </div>
        )}
      />
    </>
  );
}
