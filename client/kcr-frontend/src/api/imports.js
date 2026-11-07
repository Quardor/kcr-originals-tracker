import { apiFetch } from './client';

// snapshotDate и reportingPeriod — @RequestParam бэкенда: multipart-запрос передаёт их
// query-строкой; URLSearchParams корректно кодирует кириллицу и пробелы.
function withSnapshotParams(path, snapshotDate, reportingPeriod) {
  const params = new URLSearchParams({ snapshotDate, reportingPeriod });
  return `${path}?${params.toString()}`;
}

function fileForm(file) {
  const form = new FormData();
  form.append('file', file);
  return form;
}

/** Предпросмотр импорта клиентов: { toCreate, toUpdate, issues }. */
export function previewClientsImport(file) {
  return apiFetch('/import/clients/preview', { method: 'POST', formData: fileForm(file) });
}

/** Применение импорта клиентов: { created, updated, issues }. */
export function applyClientsImport(file) {
  return apiFetch('/import/clients', { method: 'POST', formData: fileForm(file) });
}

/**
 * Предпросмотр импорта срезов: { rows, unmatchedKcrCodes, skippedSheets, issues };
 * snapshotDate — ISO yyyy-mm-dd, reportingPeriod — выбранный отчётный период (фильтр листов).
 */
export function previewSnapshotsImport(file, snapshotDate, reportingPeriod) {
  return apiFetch(withSnapshotParams('/import/snapshots/preview', snapshotDate, reportingPeriod), {
    method: 'POST',
    formData: fileForm(file),
  });
}

/** Применение импорта срезов: { created, unmatchedKcrCodes, skippedSheets, issues }. */
export function applySnapshotsImport(file, snapshotDate, reportingPeriod) {
  return apiFetch(withSnapshotParams('/import/snapshots', snapshotDate, reportingPeriod), {
    method: 'POST',
    formData: fileForm(file),
  });
}
