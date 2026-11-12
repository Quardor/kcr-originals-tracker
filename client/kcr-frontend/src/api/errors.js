/**
 * Русский текст для кодов ошибок бэкенда (ErrorCode, см. ARCHITECTURE.md §6).
 * Для VALIDATION_FAILED и неизвестных кодов показываем message сервера.
 */
const MESSAGES = {
  AUTHENTICATION_REQUIRED: 'Требуется вход в систему',
  INVALID_CREDENTIALS: 'Неверный логин или пароль',
  INVALID_REFRESH_TOKEN: 'Сессия истекла, войдите заново',
  USERNAME_ALREADY_TAKEN: 'Имя пользователя уже занято',
  EMAIL_ALREADY_TAKEN: 'Этот email уже используется',
  TOO_MANY_AUTH_ATTEMPTS: 'Слишком много попыток. Подождите минуту и попробуйте снова',
  CLIENT_NOT_FOUND: 'Клиент не найден',
  CLIENT_ALREADY_EXISTS: 'Клиент с таким кодом KCR уже существует',
  BLANK_ORGANIZATION_NAME: 'Название организации не может быть пустым',
  INVALID_SORT_FIELD: 'Недопустимое поле сортировки',
  INVALID_SORT_DIRECTION: 'Недопустимое направление сортировки',
  IMPORT_FILE_REQUIRED: 'Выберите файл для загрузки',
  IMPORT_FILE_UNREADABLE: 'Файл не читается как книга Excel (.xlsx)',
  IMPORT_PERIOD_NOT_FOUND: 'В файле нет листа выбранного отчётного периода',
  CLIENT_ALREADY_TAKEN: 'Клиент уже взят другим оператором',
  USER_NOT_FOUND: 'Пользователь не найден',
  STAGE_CHANGE_FORBIDDEN: 'Оператор может менять только рабочие этапы своих клиентов',
  EVENT_TYPE_NOT_ALLOWED: 'Такой тип события создаётся автоматически, вручную — нельзя',
  EVENT_FIELD_NOT_ALLOWED: 'Поле недоступно для этого типа события',
  CALL_RESULT_REQUIRED: 'Укажите результат звонка',
  CLAIM_NOT_FOUND: 'Претензия не найдена',
  SANCTION_NOT_FOUND: 'Санкция не найдена',
  MALFORMED_REQUEST_BODY: 'Некорректное тело запроса',
  INVALID_REQUEST_PARAMETER: 'Некорректный параметр запроса',
  RESOURCE_NOT_FOUND: 'Адрес не найден',
  DATA_CONFLICT: 'Конфликт данных',
  INTERNAL_ERROR: 'Внутренняя ошибка сервера',
  NETWORK_ERROR: 'Не удалось соединиться с сервером',
};

export function errorMessage(error) {
  if (!error) return 'Неизвестная ошибка';
  if (error.code && MESSAGES[error.code]) return MESSAGES[error.code];
  return error.message || 'Неизвестная ошибка';
}
