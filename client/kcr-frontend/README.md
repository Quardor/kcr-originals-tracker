# kcr-frontend

SPA фронтенд системы учёта оригиналов документов (KCR Originals Tracker). Работает поверх
REST API бэкенда `server/kcr-backend` (префикс `/api/v1`).

## Стек

- React 18 (JavaScript) + Vite
- react-router-dom — маршрутизация
- без UI-библиотек и CSS-фреймворков — единый `src/styles.css`
- без axios — fetch-обёртка с автопродлением JWT (`src/api/client.js`)

## Возможности

- вход и регистрация (регистрация создаёт оператора);
- справочник клиентов: поиск по коду KCR/ИНН, фильтры (тип, KCR действует/заблокирован,
  признак), сортировка, пагинация;
- карточка клиента со всеми полями и историей срезов передачи документов
  (доля непереданных, фильтр по отчётному периоду);
- создание и изменение клиента (только администратор; PATCH — только заполненные поля);
- импорт из Excel: предпросмотр без записи и применение, отдельные выгрузки клиентов
  и срезов (только администратор).

## Запуск

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # прод-сборка в dist/
npm run preview    # локальный просмотр собранного dist/
```

Бэкенд должен быть доступен по адресу из `VITE_API_BASE_URL` (по умолчанию
`http://localhost:8080/api/v1`; CORS бэкенда в dev разрешает origin `http://localhost:5173`).
Значение переопределяется через `.env` (см. `.env.example`).

## Структура

```
src/
├── api/          — доступ к REST API: client.js (fetch-обёртка, refresh-ротация
│                   single-flight — параллельный refresh отзывает семейство токенов),
│                   auth.js, clients.js, imports.js, errors.js (коды → русский текст)
├── auth/         — сессия: jwt.js (декод claims), AuthContext.jsx
├── components/   — Layout, Protected, Pagination, ui.jsx (бейджи/спиннер/ошибки)
└── pages/        — Login, Clients, ClientCard, ClientForm, Import, NotFound
```

Роли берутся из claims access-токена (`preferred_username`, `roles`); проверка прав на
бэкенде — UI-ограничение по роли только скрывает недоступное.
