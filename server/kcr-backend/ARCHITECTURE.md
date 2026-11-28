# ARCHITECTURE — kcr-backend (краткая выжимка)

То, что нужно знать почти в каждой задаче. Разделы сжимают одноимённые разделы
[ARCHITECTURE-FULL.md](./ARCHITECTURE-FULL.md) под теми же номерами §1–§8 — подробности и
обоснования там; история работ (§9), открытые пункты (§10) и разбор ТЗ (§11) лежат только в
полном файле. Правила оформления кода — [AGENTS.md](./AGENTS.md) §2, здесь не повторяются.
Изменение архитектуры, стека, доменной модели или состояния проекта отражай в обоих файлах
в том же изменении.

## 1. Назначение проекта

**kcr-originals-tracker** — монорепозиторий сервиса контроля передачи оригиналов документов и
коммуникации с клиентами КЦР (KCR Originals Tracker).

- `server/kcr-backend` — REST-бэкенд на Spring Boot, единственный владелец данных (PostgreSQL):
  аутентификация (JWT, роли `ADMIN`/`OPERATOR`), пользователи, справочник клиентов (код KCR —
  естественный уникальный ключ) и Excel-импорт, срезы состояния передачи документов и 7 категорий
  динамики, операторский цикл (этапы, ответственный, единая история, претензии и санкции), рабочие
  списки («сегодня нужно», список на обзвон).
- `client/kcr-frontend` — SPA на React (Vite, JavaScript).
- `ml/` (kcr-ml) — сервис ИИ-помощника (Python/FastAPI): строго read-only, читает данные этого
  бэкенда через его REST API от имени пользователя.

Маршрут трафика: браузер → nginx образа фронтенда (один origin) → **весь** `/api/v1/**` в бэкенд.
Аутентификацию, роль и права проверяет только бэкенд: `/api/v1/ai/**` (домен `ai/`) он проксирует
в kcr-ml, WebSocket чата — relay. kcr-ml наружу не опубликован, отвечает только бэкенду по
внутреннему сервисному токену и сам ходит в бэкенд только GET-запросами с токеном пользователя.

## 2. Технологический стек

| Компонент | Технология (версия) |
|---|---|
| Язык, сборка | Java 17; Gradle 9.7.1 (wrapper, Kotlin DSL) |
| Каркас | Spring Boot 4.1.1: MVC, Spring Data JPA (Hibernate), Spring Security (stateless), Bean Validation, WebSocket |
| БД, миграции | PostgreSQL 16; Flyway (модуль `spring-boot-flyway`) |
| JWT | jjwt 0.12.6 — только выдача access-токенов; проверка — resource server Spring Security |
| Прочее | Bucket4j 8.21.0 + Caffeine (rate limiting), Jackson 3 (`tools.jackson.*` — в Boot 4 вместо `com.fasterxml.jackson`), Lombok, Apache POI 5.5.1 (Excel) |
| Тесты | JUnit 5 (starter'ы `webmvc-test`, `data-jpa-test`, `security-test`); заглушка kcr-ml — MockWebServer |
| Контейнер | Docker, multi-stage Dockerfile (temurin 17, non-root) |

Группа `ru.quard0r`, корневой пакет — `ru.quard0r.kcrbackend`.

## 3. Структура пакетов (package-by-feature)

Контроллер и сервисы лежат в корне пакета домена, `model/`, `repository/`, `dto/` — его подпакеты.

| Пакет | За что отвечает |
|---|---|
| `ai/` | `/api/v1/ai/**`: прокси к kcr-ml, relay WebSocket-чата, одноразовые ticket'ы |
| `auth/` | `/api/v1/auth/**`: регистрация, вход, ротация refresh-токенов, блокировка аккаунта, очистка токенов |
| `clients/` | `/api/v1/clients/**`, `/api/v1/worklist/**`, смена этапа по ролям (`StagePolicy`); `events/` — история, претензии, санкции (сущности и репозитории лежат прямо в пакете, без `model/` и `repository/`) |
| `snapshots/` | срезы и расчёт категорий динамики (`DynamicsService`) |
| `imports/` | Excel-импорт: парсеры-адаптеры → командные DTO, upsert/применение |
| `users/` | пользователи, bootstrap администратора (`AdminBootstrap`) |
| `common/` | `error/` (контракт ошибок), `validation/` (`@Inn`/`@Kpp`/`@KcrCode` — единые правила формата для DTO и парсеров), нормализаторы, `PageLimits` + `PageResponse` |
| `security/` | JWT-инфраструктура, `AuthRateLimiter` |
| `config/` | бины Spring: `SecurityConfig`, `JpaAuditingConfig` |

Направление зависимостей между доменами (по фактическому импорту):

```
ai        → security, common
auth      → users, security, common
security  → users, common
clients   → snapshots, users, common      (clients.events → clients — тот же домен)
snapshots → clients.model                 (только Client: FK и createdAt для правила «новичок»)
imports   → clients (+ clients.events, включая репозитории — ради батчевой записи),
            snapshots, users.model
config    → security, ai                  (регистрация WebSocket-хендлера чата)
```

- `common` не импортирует ничего из доменных пакетов. Контроллер не обращается к репозиториям и
  не содержит бизнес-логики; сервис не знает о HTTP; репозитории работают только с сущностями.
- Исключения: `clients ↔ snapshots` — цикл на уровне пакетов (п. 62 §10 ARCHITECTURE-FULL.md);
  `imports` пишет в историю напрямую через `ClientEventRepository` (события пакетом, `saveAll`),
  а не через `ClientEventService.record`.

## 4. Доменная модель

Таблицы полей и ограничений — §4 ARCHITECTURE-FULL.md; все таблицы созданы миграциями `V1`–`V13`.
Связи — `@ManyToOne LAZY`: к `User` — `RefreshToken`, `Client.assignee`, `ClientEvent.author`,
`CallListItem.createdBy`; к `Client` — `DocumentTransferSnapshot`, `ClientEvent`, `Claim`,
`CallListItem` (уникален: клиент в списке не более одного раза); к `Claim` — `Sanction`.

| Сущность (таблица) | Главные инварианты |
|---|---|
| `User` (`users`) | `username`/`email` — в нижнем регистре, уникальны регистронезависимо (unique-индексы по `lower(...)`); самостоятельная регистрация всегда даёт `OPERATOR` |
| `RefreshToken` (`refresh_tokens`) | в БД только SHA-256-хеш сырого UUID; `rotatedDate = null` — активный токен, значение — ротирован |
| `Client` (`clients`) | код KCR — естественный уникальный ключ (канонический вид `^[A-Z]{3}\d{5}$`, верхний регистр); обязательны код KCR, ИНН, название организации; `null` = данных нет (для булевых `null` ≠ `false`); `stage` — этап работы, отдельно от состояния документов; `assignee = null` — не взят в работу; `createdAt` — база правила «новичок», по факту момент загрузки строки в БД (п. 40 §10 ARCHITECTURE-FULL.md) |
| `DocumentTransferSnapshot` (`document_transfer_snapshots`) | только добавляются, никогда не перезаписываются; уникальности (клиент, период, дата) нет; доля непереданных в БД не хранится, считается при выдаче; `dynamicsCategory` и `previousNotTransferred` заполняются при импорте, списки пересчитывают категорию по текущей истории |
| `CallListItem` (`call_list_items`) | единый список на обзвон: один для всех операторов, изменяет и пересоставляет только администратор |
| `ClientEvent` (`client_events`) | единая история: любая мутация оставляет запись с автором и временем, выдача от новых к старым; поля звонка разрешены только `CALL`, результат звонка для `CALL` обязателен |
| `Claim` (`claims`), `Sanction` (`sanctions`) | добавляет и меняет только администратор; `number` претензии иммутабелен; название санкции — свободный текст; каждая мутация дублируется событием `CLAIM`/`SANCTION` |

Enum'ы в БД — `@Enumerated(EnumType.STRING)` (varchar + CHECK-список); расширение справочника —
новая миграция с заменой CHECK. Значения остальных справочников — §4 ARCHITECTURE-FULL.md.

- `WorkStage`: `NOT_IN_WORK → IN_POOL → FIRST_CALL → WAITING_CLIENT_DEADLINE → RECHECK →
  SECOND_CALL → CLAIM_WORK → COMPLETED`. Порядок переходов не контролируется; тот же этап —
  no-op без события; взятие клиента (`take`) этап не меняет. Права (`clients/StagePolicy`):
  админ — любой этап, оператор — рабочие этапы (кроме `NOT_IN_WORK`/`IN_POOL`) своих клиентов.
- `DynamicsCategory`: `NO_DEBT`, `FIRST_OCCURRENCE`, `DECREASING`, `UNCHANGED_OR_GROWING`,
  `REAPPEARED`, `PERSISTENT`, `NEWCOMER_RISK`. Считает `snapshots/DynamicsService`
  (`classifySnapshot`) при импорте (категория сохраняется в строку среза) и на лету в списках;
  «предыдущий» — соседний срез по `(snapshotDate, id)` без разбивки по периодам (п. 42 §10
  ARCHITECTURE-FULL.md); «новичок» — менее 6 месяцев между `clients.created_at` и датой среза.
- `ClientEventType`: `CALL`, `LETTER_SENT`, `COMMENT` — вручную через `POST /clients/{id}/events`
  (системный тип вручную отклоняется — `EVENT_TYPE_NOT_ALLOWED`); `STAGE_CHANGED`,
  `ASSIGNEE_CHANGED`, `IMPORT`, `CLAIM`, `SANCTION` — только сервисным слоем
  (`ClientEventService.record`). `CALL` может сменить этап (отдельное событие `STAGE_CHANGED` в
  той же транзакции) и задаёт `clients.next_contact_at`.

Первичные ключи — суррогатные `id`: у `clients`, `client_events`, `document_transfer_snapshots`
единственный генератор — sequence с шагом 50 (`allocationSize = 50`, батчинг INSERT; `V12`,
`V13`), в том числе для вставок мимо Hibernate; остальные таблицы — `IDENTITY`.

## 5. Аутентификация (JWT)

Схема stateless: без сессий, CSRF отключён, серверного блок-листа access-токенов нет.

- **Токены и пароли.** Access — HS256 (ключ `jwt.secret`), subject = id пользователя, claims
  `preferred_username`, `email`, `roles`, `jti`, срок — `jwt.access-expiration` (15 мин); refresh
  — срок `jwt.refresh-expiration` (7 дней). Пароли — только хеш `DelegatingPasswordEncoder`
  (`{bcrypt}$2a$…`; хеши без префикса матчатся fallback-BCrypt).
- **Проверка access-токена** — resource server: `NimbusJwtDecoder` →
  `security/JwtAuthenticationConverter` (subject → id, пользователь — через Caffeine-кэш с TTL
  30 с) → `UserAuthenticationToken` с principal `CustomUserDetails`. Любая неудача (испорченный,
  истёкший токен, неизвестный пользователь) — 401, не 500.
- **`login`** — любая `AuthenticationException` → единый 401 «Invalid credentials», в том числе
  при блокировке аккаунта.
- **`refresh`** — `RefreshTokenService.rotate(rawToken)` в одной транзакции под `SELECT … FOR
  UPDATE` (только строка `refresh_tokens`, без JOIN FETCH): конкурентные запросы с одним токеном
  сериализуются. Повторное предъявление ротированного токена (reuse-detection) — отзыв **всех**
  refresh-токенов пользователя и 401; неизвестный и просроченный — 401. `AuthService.refresh`
  намеренно без `@Transactional`: иначе откат по 401 отменил бы уже сделанный отзыв.
- **`logout`** — требует access-токен, отзывает все refresh-токены пользователя (все устройства),
  204; access-токен валиден до истечения срока.
- **Антибрутфорс** — rate limiting `POST /api/v1/auth/login|register` (Bucket4j, ключ — пара
  username+IP, `security/AuthRateLimiter`; переполнение → 429 `TOO_MANY_AUTH_ATTEMPTS`; состояние
  in-memory, локально для инстанса) и блокировка аккаунта (`users.failed_attempts`/`locked_until`:
  5 неудач → блок на 15 минут; счётчик — атомарный bulk-UPDATE в отдельной транзакции
  `REQUIRES_NEW`, успех сбрасывает).
- **Маршруты и ответы.** Публичны `/api/v1/auth/**` (кроме `/api/v1/auth/logout`) и служебный
  `/error`, всё остальное — только аутентифицированным; `register` публичен и всегда создаёт
  `OPERATOR`. Права — `@PreAuthorize("hasRole('…')")`. 401 — всегда с телом `ErrorResponse`
  (`RestAuthenticationEntryPoint`, причина только в debug-лог); 403 от `@PreAuthorize` — с пустым
  телом, мимо единого контракта (§6).
- **Администратор.** Если в БД нет ни одного `ADMIN`, старт создаёт его из
  `ADMIN_USERNAME`/`ADMIN_PASSWORD` (`AdminBootstrap`: в prod без них — fail-fast).
- **kcr-ml за бэкендом.** Домен `ai/` после проверки токена и прав добавляет внутренний
  сервисный токен (`app.ml.internal-token`, ≥ 32 байта, иначе приложение не стартует) и заголовки
  `X-Kcr-User-Id`/`X-Kcr-User-Role`, токен пользователя передаёт как есть — права на данные
  снова считает бэкенд. `prioritize` и `export/xlsx` — только `ADMIN`. WebSocket чата
  (`/api/v1/ai/chat`) открывается по одноразовому ticket'у (`POST /api/v1/ai/chat/ticket`,
  привязан к access-токену; повторный или чужой — 401 до upgrade). kcr-ml недоступен или отверг
  внутренний токен — 503 `AI_SERVICE_UNAVAILABLE`, не 401.

## 6. Исключения и контракт ошибок

- Сервисы бросают единственное исключение `DomainException(ErrorCode)` (`common/error/`);
  новое поведение ошибки — новая константа `ErrorCode` («код, HTTP-статус, сообщение по
  умолчанию»), а не новый класс.
- Маппинг в HTTP — только `GlobalExceptionHandler`. Единый формат `ErrorResponse`:
  `{ "code", "message", "timestamp" }`, `timestamp` — ISO-8601, не epoch-millis.
  Неаутентифицированным тот же контракт отдаёт `RestAuthenticationEntryPoint` (401
  `AUTHENTICATION_REQUIRED`).
- `DataIntegrityViolationException` (гонка «exists-проверка + save») различается по имени
  нарушенного констрейнта/индекса: уникальность username, email и кода KCR → точный код 409
  (`USERNAME_ALREADY_TAKEN`, `EMAIL_ALREADY_TAKEN`, `CLIENT_ALREADY_EXISTS`), прочее →
  `DATA_CONFLICT`.
- Непредвиденное — catch-all `INTERNAL_ERROR` (500): причина только в логе, ответ нейтрален.

Коды по статусам: 401 — `AUTHENTICATION_REQUIRED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN`;
403 — `STAGE_CHANGE_FORBIDDEN`; 404 — `CLIENT_NOT_FOUND`, `USER_NOT_FOUND`, `CLAIM_NOT_FOUND`,
`SANCTION_NOT_FOUND`, `RESOURCE_NOT_FOUND`; 409 — `CLIENT_ALREADY_TAKEN`, `DATA_CONFLICT` и коды
уникальности из пункта выше; 429 — `TOO_MANY_AUTH_ATTEMPTS`; 503 — `AI_SERVICE_UNAVAILABLE`;
500 — `INTERNAL_ERROR`, `SHA256_UNAVAILABLE`; остальные — 400 (валидация `VALIDATION_FAILED`,
разбор запроса, правила домена). Полный реестр с источниками — §6 ARCHITECTURE-FULL.md.

Известные пробелы контракта (устранение — п. 43 §10 ARCHITECTURE-FULL.md): отказ
`@PreAuthorize` — 403 с пустым телом; неподдерживаемый метод, неподдерживаемый `Content-Type` и
multipart-запрос импорта без части `file` — 500 `INTERNAL_ERROR` вместо 405, 415 и 400.

## 7. Конфигурация

`src/main/resources/application.yaml` — общая часть; профили `dev` и `prod`
(`application-dev.yaml`, `application-prod.yaml`); без явного профиля (`bootRun`, тесты) активен `dev`.

- Общая часть: `spring.jpa.hibernate.ddl-auto=validate`, OSIV выключен, батчинг
  (`hibernate.jdbc.batch_size=50`, `order_inserts`, `order_updates`), лимиты multipart (10 МБ / 15 МБ).
- `dev`: БД compose-стенда `jdbc:postgresql://localhost:5433/kcr` (пользователь `kcr`), CORS
  `http://localhost:5173`, подключает `.env`. `prod`: подключение к БД и `JWT_SECRET` строго из
  окружения, без значений по умолчанию — незаданные переменные не дают приложению стартовать.

| Переменная | Профили | Назначение |
|---|---|---|
| `DB_PASSWORD`, `JWT_SECRET`, `ML_INTERNAL_TOKEN` | dev, prod | пароль БД; HMAC-ключ JWT (≥ 32 байта); внутренний токен для kcr-ml (≥ 32 байта, в kcr-ml — `AUTH_INTERNAL_TOKEN`) |
| `DB_URL`, `DB_USERNAME`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `ML_BASE_URL` | prod; в dev опциональны | подключение к БД; первый администратор; адрес kcr-ml |
| `APP_CORS_ALLOWED_ORIGINS` | опциональна | CORS-origins через запятую; пустой список — кросс-доменные запросы не разрешены |

- Секреты в репозитории не хранятся. Локально — `server/kcr-backend/.env` (копия шаблона
  `.env.example`, в `.gitignore`), его подключает профиль `dev`, переменные окружения
  приоритетнее. Для контейнерного стенда те же значения задаёт **корневой** `.env` (шаблон —
  корневой `.env.example`); в контейнере `SPRING_PROFILES_ACTIVE=prod`, часовой пояс UTC.
- Прикладные свойства: `app.cleanup.refresh-token-cron` (`0 0 4 * * *`);
  `app.admin.username`/`app.admin.password`; антибрутфорс —
  `app.security.auth-rate-limit.capacity` (10) с `refill-duration` (1m) и
  `app.security.account-lockout.max-failed-attempts` (5) с `lock-duration` (15m); kcr-ml —
  `app.ml.base-url`, `app.ml.internal-token`.
- Схема БД — Flyway: миграции `V1`–`V13` в `src/main/resources/db/migration` применяются при
  старте до инициализации JPA (перечень — §7 ARCHITECTURE-FULL.md). Применённые файлы не
  редактируются — любое изменение схемы в новую `V<n>__*.sql`.
- Задача Gradle `test` без переменной окружения подставляет заглушку `ML_INTERNAL_TOKEN` —
  `./gradlew build` не требует локального секрета.

## 8. Сборка и запуск

Предусловия: JDK 17 (toolchain — Gradle сам не скачивает), запущенный контейнер `kcr-db-1`
(`docker start kcr-db-1` из корня репозитория; создаётся `docker compose up -d db`) и файл
`server/kcr-backend/.env` с секретами (шаблон — `.env.example`).

```bash
# из каталога server/kcr-backend
./gradlew build          # сборка + все тесты (нужен PostgreSQL)
./gradlew test           # только тесты
./gradlew bootRun        # http://localhost:8080 (профиль dev, БД localhost:5433)
./gradlew bootRun --args='--create-admin'   # одноразовый администратор, приложение завершается
```

- Отчёт о тестах — `build/reports/tests/test/index.html`.
- Интеграционные тесты идут в ту же базу `kcr-db-1` и применяют к ней миграции; юнит-тесты не
  ловят проблемы со схемой и посевом данных (п. 38 §10 ARCHITECTURE-FULL.md) — прогоняй и их.
  Упавшие посреди сценария тесты оставляют в dev-БД данные (п. 18 §10 ARCHITECTURE-FULL.md).
- `app` собирается из `Dockerfile` (multi-stage, non-root) с профилем `prod` и работает в UTC;
  тесты при сборке образа не запускаются. Образ иммутабелен: после переключения веток или мержа
  пересобери его (`docker compose up -d --build app`).

Контейнерный стенд (весь монорепо; из корня репозитория, настройки — корневой `.env`):

```bash
docker compose up -d --build     # db, app (:8080), ml (порт не публикуется), frontend (:5173)
docker compose logs -f app
docker compose down              # остановить; down -v — вместе с данными БД
```

## Карта полного документа

| Раздел [ARCHITECTURE-FULL.md](./ARCHITECTURE-FULL.md) | Когда открывать |
|---|---|
| §1 Назначение проекта | подробнее о фронтенде, kcr-ml, маршруте трафика |
| §2 Технологический стек | назначение и версия каждой зависимости |
| §3 Структура пакетов | полное дерево: какой класс где лежит |
| §4 Доменная модель | таблицы полей и индексов, алгоритм `classifySnapshot`, автокомментарии |
| §5 Аутентификация | обоснование решений, параметры антибрутфорса, ticket чата |
| §6 Исключения и контракт ошибок | полный реестр кодов с источниками |
| §7 Конфигурация | таблица переменных, миграции `V1`–`V13`, `.env` и compose |
| §8 Сборка и запуск | устройство `Dockerfile` |
| §9 Текущее состояние | что реализовано и состояние проверок («Сводка»); что, когда и почему сделано («Хронология работ») |
| §10 Требуемые изменения | открытые проблемы и целевые решения (пункты с постоянной нумерацией): перед правкой домена посмотри, нет ли пункта про него |
| §11 Требования из ТЗ и план домена | границы MVP, бизнес-правила, целевая модель, **полный список REST-эндпоинтов (11.4)**, открытые вопросы к заказчику, порядок внедрения |
