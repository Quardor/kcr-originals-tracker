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
