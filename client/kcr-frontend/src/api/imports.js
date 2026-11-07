import { apiFetch } from './client';

// snapshotDate — @RequestParam бэкенда: multipart-запрос передаёт его query-строкой.
function withDate(path, snapshotDate) {
  return `${path}?snapshotDate=${encodeURIComponent(snapshotDate)}`;
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

/** Предпросмотр импорта срезов: { rows, unmatchedKcrCodes, issues }; snapshotDate — ISO yyyy-mm-dd. */
export function previewSnapshotsImport(file, snapshotDate) {
  return apiFetch(withDate('/import/snapshots/preview', snapshotDate), {
    method: 'POST',
    formData: fileForm(file),
  });
}

/** Применение импорта срезов: { created, unmatchedKcrCodes, issues }. */
export function applySnapshotsImport(file, snapshotDate) {
  return apiFetch(withDate('/import/snapshots', snapshotDate), {
    method: 'POST',
    formData: fileForm(file),
  });
}
