/**
 * Русские подписи доменных справочников бэкенда (§11.3 ARCHITECTURE.md).
 * Наборы значений зеркалят enum'ы WorkStage/ClientEventType/CallResult/
 * ClaimStatus/SanctionStatus — расширение словарей требует правки и здесь.
 */

export const WORK_STAGES = [
  { value: 'NOT_IN_WORK', label: 'Не в работе' },
  { value: 'IN_POOL', label: 'В общем пуле' },
  { value: 'FIRST_CALL', label: 'Первый звонок' },
  { value: 'WAITING_CLIENT_DEADLINE', label: 'Ожидание срока клиента' },
  { value: 'RECHECK', label: 'Повторная проверка' },
  { value: 'SECOND_CALL', label: 'Второй звонок' },
  { value: 'CLAIM_WORK', label: 'Претензионная работа' },
  { value: 'COMPLETED', label: 'Цикл завершён' },
];

const STAGE_LABELS = Object.fromEntries(WORK_STAGES.map((s) => [s.value, s.label]));

export function stageLabel(value) {
  return STAGE_LABELS[value] ?? value ?? '—';
}

/** Оператор меняет только рабочие этапы; NOT_IN_WORK/IN_POOL — за админом (§11.4). */
export const WORK_STAGES_OPERATOR = WORK_STAGES.filter(
  (s) => s.value !== 'NOT_IN_WORK' && s.value !== 'IN_POOL'
);

export const EVENT_TYPE_LABELS = {
  CALL: 'Звонок',
  LETTER_SENT: 'Письмо отправлено',
  COMMENT: 'Комментарий',
  STAGE_CHANGED: 'Смена этапа',
  ASSIGNEE_CHANGED: 'Ответственный',
  IMPORT: 'Импорт',
  CLAIM: 'Претензия',
  SANCTION: 'Санкция',
};

export function eventTypeLabel(value) {
  return EVENT_TYPE_LABELS[value] ?? value ?? '—';
}

export const MANUAL_EVENT_TYPES = [
  { value: 'CALL', label: 'Звонок' },
  { value: 'LETTER_SENT', label: 'Письмо отправлено' },
  { value: 'COMMENT', label: 'Комментарий' },
];

export const CALL_RESULTS = [
  { value: 'CONNECTED', label: 'Дозвонился, разговор состоялся' },
  { value: 'NO_ANSWER', label: 'Не дозвонился' },
  { value: 'CALLBACK_LATER', label: 'Попросил перезвонить позже' },
  { value: 'WRONG_NUMBER', label: 'Номер недоступен или неверен' },
];

const CALL_RESULT_LABELS = Object.fromEntries(CALL_RESULTS.map((r) => [r.value, r.label]));

export function callResultLabel(value) {
  return CALL_RESULT_LABELS[value] ?? value ?? '—';
}

export const CLAIM_STATUSES = [
  { value: 'NEW', label: 'Новая' },
  { value: 'SENT', label: 'Направлена клиенту' },
  { value: 'RECEIVED', label: 'Получена клиентом' },
  { value: 'SATISFIED', label: 'Закрыта: документы переданы' },
  { value: 'CANCELLED', label: 'Отменена' },
];

const CLAIM_STATUS_LABELS = Object.fromEntries(CLAIM_STATUSES.map((s) => [s.value, s.label]));

export function claimStatusLabel(value) {
  return CLAIM_STATUS_LABELS[value] ?? value ?? '—';
}

export const SANCTION_STATUSES = [
  { value: 'PLANNED', label: 'Запланирована' },
  { value: 'APPLIED', label: 'Применена' },
  { value: 'CANCELLED', label: 'Отменена' },
];

const SANCTION_STATUS_LABELS = Object.fromEntries(SANCTION_STATUSES.map((s) => [s.value, s.label]));

export function sanctionStatusLabel(value) {
  return SANCTION_STATUS_LABELS[value] ?? value ?? '—';
}

/** Дата-время события истории в локальном представлении. */
export function formatDateTime(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('ru-RU');
}
