# ARCHITECTURE — kcr-backend

Архитектурная документация kcr-backend: назначение, стек, структура пакетов, доменная модель,
аутентификация, текущее состояние, требования из ТЗ и план домена. Любое изменение структуры
пакетов, стека или доменной модели **должно** отражаться в этом файле.

---

## 1. Назначение проекта

**kcr-originals-tracker** — монорепозиторий сервиса контроля передачи оригиналов документов и
коммуникации с клиентами КЦР (KCR Originals Tracker). Обзор монорепозитория — в корневом
[README.md](../../README.md); документация по сервисам — в их каталогах.

`server/kcr-backend` — REST-бэкенд на Spring Boot, единственный владелец данных (PostgreSQL).
Отвечает за:

- аутентификацию и авторизацию (JWT: access- + refresh-токены, роли `ADMIN`/`OPERATOR`);
- хранение пользователей и их ролей, bootstrap первого администратора;
- справочник клиентов (сущность `Client`, код KCR — естественный уникальный ключ) и Excel-импорт
  клиентов и срезов;
- срезы состояния передачи документов по клиентам и расчёт категорий динамики (7 категорий, §11.2);
- операторский цикл: этапы работы, ответственный, единая история клиента, претензии и санкции;
- рабочие списки: «сегодня нужно» и единый закреплённый список на обзвон.

`client/kcr-frontend` — SPA на React (Vite, JavaScript): вход/регистрация, справочник клиентов
с фильтрами и пагинацией, карточка клиента (история, события, претензии, срезы, AI-сводка),
ручное создание/правка и Excel-импорт (предпросмотр/применение), «Сегодня», список на обзвон,
страницы ИИ-помощника. Потребляет `/api/v1/**` этого бэкенда и `/api/v1/ai/**` сервиса `ml/`.

`ml/` (kcr-ml) — сервис ИИ-помощника на Python/FastAPI: чат по данным, сводка перед звонком,
приоритизация, аналитика и экспорт в xlsx. Строго read-only: читает данные этого бэкенда через
его REST API от имени пользователя, access-токены проверяет общим секретом (`jwt.secret`) и
собственных токенов не выдаёт. Документация — [ml/ARCHITECTURE.md](../../ml/ARCHITECTURE.md).

Маршрут трафика: браузер → nginx образа фронтенда (один origin) → `/api/v1/ai/**` в kcr-ml,
остальной `/api/v1/**` — в бэкенд; kcr-ml → бэкенд только GET-запросами (используемые
эндпоинты перечислены в §11.4).

## 2. Технологический стек

| Компонент | Технология | Версия |
|---|---|---|
| Язык | Java (toolchain) | 17 |
| Сборка | Gradle, Kotlin DSL | 9.7.1 (wrapper) |
| Framework | Spring Boot | 4.1.1 |
| ORM | Spring Data JPA (Hibernate) | из BOM Boot |
| БД | PostgreSQL | 16 (контейнер `kcr-db-1` compose-стенда, образ `postgres:16-alpine`) |
| Миграции | Flyway (`spring-boot-flyway` + `flyway-database-postgresql`) | из BOM Boot |
| Безопасность | Spring Security (stateless, JWT: проверка access-токенов — resource server, `spring-boot-starter-oauth2-resource-server`) | из BOM Boot |
| JWT | jjwt (api/impl/jackson) — только выдача access-токенов | 0.12.6 |
| Rate limiting | Bucket4j (`bucket4j_jdk17-core`) + Caffeine (кэш бакетов, версия из BOM Boot) | 8.21.0 |
| Валидация | Bean Validation (`spring-boot-starter-validation`; для параметров контроллеров — встроенная method validation, без `@Validated`) | из BOM Boot |
| JSON | Jackson 3 (пакет `tools.jackson.*` — в Boot 4 вместо `com.fasterxml.jackson`) | из BOM Boot |
| Бойлерплейт | Lombok | из BOM Boot |
| Excel-импорт | Apache POI (`poi-ooxml`) | 5.5.1 |
| Тесты | JUnit 5: `spring-boot-starter-webmvc-test` (MockMvc), `-data-jpa-test`, `-security-test`; интеграционные сценарии идут в реальный PostgreSQL (§8) | из BOM Boot |
| Контейнер | Docker, multi-stage Dockerfile (temurin `17-jdk-noble` → `17-jre-noble`, non-root) | см. §8 |

Группа: `ru.quard0r`, корневой пакет: `ru.quard0r.kcrbackend`.

## 3. Структура пакетов (package-by-feature)

Домены — feature-пакеты (§11.5; переход завершён 2026-11-08 веткой
`refactor/package-by-feature`): контроллер и сервисы — в корне пакета домена,
`model/`, `repository/`, `dto/` — подпакеты домена; вложенная под-домена
(`clients/events/`) — по той же схеме.

```
ru.quard0r.kcrbackend
├── KcrBackendApplication   — точка входа (@SpringBootApplication)
├── auth/                   — домен «аутентификация» (2026-11-08): AuthController —
│                             /api/v1/auth/** (register/login/refresh, §5); AuthService;
│                             RefreshTokenService — ротация refresh-токенов с
│                             reuse-detection, AccountLockoutService — счётчик неудачных
│                             входов и блокировка аккаунта; RefreshTokenCleanupJob —
│                             ежедневная очистка просроченных refresh-токенов (§5);
│                             model/ — RefreshToken; repository/ — RefreshTokenRepository;
│                             dto/ — LoginRequest, RegisterRequest, RefreshRequest
│                             (нормализация в компакт-конструкторах до Bean Validation,
│                             см. §5), AuthResponse
├── clients/                — домен «клиенты» (feature-пакет §11.5, перенесён 2026-11-08):
│                             ClientController — /api/v1/clients/** (карточки, фильтры,
│                             take/assignment/stage, пагинация валидируется constraint-
│                             аннотациями через PageLimits, п. 28 §10), WorklistController —
│                             /api/v1/worklist/** (today, preview, generate, call-list),
│                             ClientService, WorklistService,
│                             StagePolicy — правила смены этапа по ролям (§11.2);
│                             model/ — Client, ClientType, WorkStage, CallListItem;
│                             repository/ — ClientRepository, CallListItemRepository;
│                             dto/ — клиентские request/response, WorklistFilterRequest,
│                             CallListClientResponse, CallListSummaryResponse;
│                             events/ — под-домен «история и претензионная работа». Сущности,
│                             enum'ы и репозитории лежат прямо в пакете, без model/ и
│                             repository/: ClientEvent, Claim, Sanction, enum'ы ClientEventType/
│                             CallResult/ClaimStatus/SanctionStatus, ClientEventRepository,
│                             ClaimRepository, SanctionRepository. Сервисы: ClientEventService
│                             (выдача истории от новых к старым, ручные CALL/LETTER_SENT/
│                             COMMENT, системные записи мутаций через record), ClaimService.
│                             Контроллеры: ClientEventController (/clients/{id}/events),
│                             ClaimController (claims/sanctions, §11.4); dto/ — события и претензии
├── common/                 — общий код без зависимостей от слоёв: EmailNormalizer (email-поля
│                             запросов, §5), KcrCodeNormalizer (канонический вид кода KCR, §4),
│                             UserIdentityNormalizer (username/email — нижний регистр, §5),
│                             PageLimits + дженерик PageResponse (пагинация, §3/§11.4);
│                             validation/ — доменные constraint-аннотации @Inn / @Kpp / @KcrCode
│                             с валидаторами — единый источник правил формата для DTO и
│                             Excel-парсеров (п. 29 §10); error/ — контракт ошибок (§6):
│                             ErrorCode — enum «код, HTTP-статус, сообщение»,
│                             DomainException — единственное доменное исключение,
│                             ErrorResponse — исходящий формат ошибки,
│                             GlobalExceptionHandler — маппинг в ErrorResponse, включая
│                             method-validation параметров и catch-all 500 (п. 32 §10)
├── config/                 — конфигурация Spring (SecurityConfig: security-цепочка, CORS, бины;
│                             JpaAuditingConfig — @CreatedDate, п. 24 §10)
├── imports/                — Excel-импорт (feature-пакет §11.5, включает ImportController;
│                             `import` — ключевое слово Java):
│                             ClientExcelParser / SnapshotExcelParser — тонкие адаптеры реальных
│                             выгрузок → командные DTO (§11.6); форматы ИНН/КПП/кода KCR
│                             проверяются общими правилами common/validation; ExcelCells —
│                             чтение ячеек, нормализация заголовков, поиск колонки кода KCR;
│                             ParseResult/ParseIssue; dto/ (командные DTO + отчёты preview/apply);
│                             ClientUpsertService / SnapshotImportService — upsert и применение
│                             (записывают события IMPORT в историю, §11.2);
│                             REST-эндпоинты — ImportController (§11.4, 2026-11-04)
├── security/               — JWT-инфраструктура (JwtService — выдача access-токенов,
│                             JwtAuthenticationConverter — валидированный токен →
│                             аутентификация (Caffeine-кэш principal'ов),
│                             CustomUserDetailsService, UserAuthenticationToken,
│                             RestAuthenticationEntryPoint)
│                             + AuthRateLimiter — антибрутфорс-лимитер login/register (см. §5)
├── snapshots/              — домен «срезы»: DynamicsService — детерминированный расчёт
│                             категорий динамики срезов (§11.2); model/ —
│                             DocumentTransferSnapshot, DynamicsCategory; repository/ —
│                             DocumentTransferSnapshotRepository; dto/ —
│                             SnapshotResponse, ClientDebtInfo
└── users/                  — домен «пользователи» (2026-11-08): UserController —
                              /api/v1/users (справочник для переназначения ответственного,
                              только админ, §11.4); UserService; AdminBootstrap — bootstrap
                              первого администратора и команда --create-admin (§5);
                              model/ — User, Role; repository/ — UserRepository;
                              dto/ — UserResponse
```

Направление зависимостей между пакетами — по фактическому импорту (сверено 2026-11-28).
`common` не импортирует ничего из доменных пакетов; `config` зависит только от `security`;
`users` — только от `common`:

```
auth      → users, security, common
security  → users, common
clients   → snapshots, users, common      (clients.events → clients — тот же домен)
snapshots → clients.model                 (только Client: FK и createdAt для правила «новичок»)
imports   → clients (+ clients.events, включая репозитории — ради батчевой записи),
            snapshots, users.model
```

- Контроллер не обращается к репозиториям и не содержит бизнес-логики.
- Сервис не знает о HTTP (никаких HttpStatus/RequestEntity внутри сервисов).
- Репозитории работают только с сущностями; DTO в них не передаются.

Исключения из правил слоёв, которые стоит знать: `clients ↔ snapshots` — цикл на уровне
пакетов (п. 62 §10); `imports` пишет в историю клиента напрямую через `ClientEventRepository`,
а не через `ClientEventService.record` (чтобы сохранять события пакетом, `saveAll`).

## 4. Доменная модель

Раздел описывает реализованные сущности. Все целевые таблицы ТЗ созданы миграциями
`V1`–`V12` (схемы таблиц — в SQL-файлах `src/main/resources/db/migration`); ИИ-помощник реализован
отдельным сервисом `ml/` и своих таблиц в этой БД не имеет.

Первичные ключи — суррогатные `id`. Генерация: у `clients`, `client_events` и
`document_transfer_snapshots` — явные sequence-ы с шагом 50 (`V12__batch_sequences.sql`;
`@GeneratedValue(strategy = SEQUENCE)` + `allocationSize = 50` — инстанс берёт у sequence пул
id и выдаёт их подряд, что позволяет Hibernate батчить INSERT при импорте); остальные таблицы —
`IDENTITY`. Колонки `id` трёх первых таблиц при этом сохранили свойство IDENTITY со старым
счётчиком, то есть у таблицы осталось два независимых генератора (п. 38 §10).

### User (`users`)
| Поле | Тип | Ограничения |
|---|---|---|
| id | long | PK, IDENTITY |
| username | String | not null; хранится в нижнем регистре (нормализация `common/UserIdentityNormalizer` при записи и поиске), уникальность регистронезависима — функциональный unique-индекс `idx_users_username_lower` по `lower(username)` (`V7__users_case_insensitive_identity.sql`, п. 30 §10) |
| password | String | not null (хеш пароля, формат `{bcrypt}$2a$…`, см. §5) |
| email | String | опционален; как username — нижний регистр + функциональный unique-индекс `idx_users_email_lower` (NULL не конфликтуют) |
| role | Role | not null, `@Enumerated(EnumType.STRING)` |
| failedAttempts | int | колонка `failed_attempts`, not null, default 0 — счётчик неудачных попыток входа (антибрутфорс, §5; `V6__user_account_lockout.sql`); инкремент/сброс — атомарные bulk-UPDATE (п. 31 §10) |
| lockedUntil | Instant | колонка `locked_until`, опционален — до этого момента вход запрещён (блокировка после N неудач); null/прошлое — не заблокирован |

### Role (enum)
`ADMIN`, `OPERATOR`. При самостоятельной регистрации (`register`) всегда назначается `OPERATOR`.
В БД роль хранится именем значения (`@Enumerated(EnumType.STRING)`, varchar + CHECK по списку
значений) — порядок значений enum на данные не влияет. Разовая миграция ORDINAL → STRING
(2026-10-25) выполнялась вручную ещё до внедрения Flyway, скрипт не сохранился; с `V1__init.sql`
колонка создаётся сразу как varchar + CHECK.

### RefreshToken (`refresh_tokens`)
| Поле | Тип | Ограничения |
|---|---|---|
| id | long | PK, IDENTITY |
| tokenHash | String | not null, unique; колонка `token_hash` — SHA-256 от сырого UUID |
| user | User | @ManyToOne LAZY, not null |
| expiryDate | Instant | not null |
| rotatedDate | Instant | колонка `rotated_date`, опционален: null — активный токен, значение — уже ротирован (признак для reuse-detection, см. §5) |

Индекс `idx_refresh_tokens_user_id (user_id)` (миграция `V5__refresh_tokens_user_id_index.sql`):
Postgres не создаёт индексы на FK-колонки автоматически — без него отзыв семейства токенов
(`deleteByUser`) и logout уходили в seq scan.

### Client (`clients`)
Первая предметная сущность — карточка клиента, идентифицируемого кодом KCR. Код KCR — естественный
уникальный ключ; PK — суррогатный `id` по конвенции остальных таблиц. Обязательные поля — код KCR,
ИНН, название организации; остальные заполняются по мере наличия в выгрузке (null = данных нет,
для булевых полей null ≠ false).

| Поле | Тип | Ограничения |
|---|---|---|
| id | Long | PK, SEQUENCE `clients_seq` (шаг 50, V12) |
| kcrCode | String | not null, unique — основной идентификатор клиента; канонический вид `^[A-Z]{3}\d{5}$` — при записи нормализуется к верхнему регистру (`@KcrCode` + `common/KcrCodeNormalizer`, парсер импорта — то же правило), уникальность обычным unique (citext не нужен, пп. 29–30 §10) |
| inn | String | not null, varchar(12) — 10 или 12 цифр (`@Inn`: единое с парсером импорта правило, юрлицо с 10-значным ИНН создаётся и вручную, п. 29 §10) |
| kpp | String | varchar(9), опционален — 9 цифр (`@Kpp`) |
| organizationName | String | not null |
| internalId | String | опционален — внутренний ID / лицевой счёт из выгрузки |
| clientType | ClientType | enum, опционален; в БД varchar + CHECK по списку значений (как `role` у `users`) |
| kcrActive | Boolean | опционален — действует ли KCR; null = нет данных в выгрузке |
| kcrBlocked | Boolean | опционален — заблокирован ли KCR; null = нет данных в выгрузке |
| tariff | String | опционален |
| ekbTravelTime | String | опционален — время от Екатеринбурга, формат из выгрузки |
| manager | String | опционален |
| flags | List\<String\> | колонка `flags varchar(255)[]` — специальные признаки (VIP, интегратор и др.), множество значений |
| contactPerson | String | опционален — контактное лицо клиента |
| contactPhone | String | опционален |
| contactEmail | String | опционален |
| stage | WorkStage | not null, default `NOT_IN_WORK` (`V8__client_work_fields.sql`) — этап работы, хранится отдельно от состояния передачи документов (прямое требование ТЗ); правила смены — `clients/StagePolicy` |
| assignee | User | @ManyToOne LAZY, опционален — ответственный оператор; null = не взят в работу; индекс `idx_clients_assignee_id` |
| nextContactAt | LocalDate | опционален — дата следующего контакта, обновляется событием звонка; частичный индекс `idx_clients_next_contact_at` под список «сегодня нужно» |
| createdAt | Instant | not null — появление в сервисе (`@CreatedDate`, п. 24 §10), база правила «новичок < 6 месяцев»; фактически — момент загрузки строки в эту БД, а не дата подключения клиента (п. 40 §10) |

### ClientType (enum)
`VIP`, `TECH_PARTNER`, `WORKS_VIA_MP`, `NEWCOMER`. Хранится именем значения
(`@Enumerated(EnumType.STRING)`, varchar + CHECK — см. Role).

### WorkStage (enum)
`NOT_IN_WORK → IN_POOL → FIRST_CALL → WAITING_CLIENT_DEADLINE → RECHECK → SECOND_CALL →
CLAIM_WORK → COMPLETED` (`V8`, varchar + CHECK). `IN_POOL` — администратор отобрал клиента
в общий пул, оператор ещё не взял. Порядок переходов не контролируется (последовательность
может отличаться по клиентам, §11.2); права на смену — по ролям: админ — любой этап, оператор —
только рабочие (`isWorkStage()`: всё кроме `NOT_IN_WORK`/`IN_POOL`) и только своих клиентов
(решение по вопросу 1 §11.7, 2026-11-08). Повторная установка того же этапа — no-op без события.
Взятие клиента (`take`) этап не меняет: клиент с ответственным может оставаться `IN_POOL` или даже
`NOT_IN_WORK`, пока оператор или админ не сменит этап вручную либо звонком (п. 44 §10).

### DocumentTransferSnapshot (`document_transfer_snapshots`)
Срез состояния передачи документов клиента: сколько документов клиент должен был передать за отчётный
период и сколько ещё не передано, по состоянию на дату среза. Доменные правила:

- срезы только добавляются, никогда не перезаписываются: по одному отчётному периоду возможно
  несколько срезов (отслеживание динамики, например помесячно); уникальности
  (клиент, период, дата) нет — каждая загрузка создаёт новую строку;
- `totalDocuments` опционален: повторный срез может загружаться без поля «Всего документов» —
  при импорте значение копируется из последнего среза того же клиента и периода
  (`SnapshotImportService.resolveTotalFromHistory`). В реальных выгрузках колонки «Всего» нет,
  поэтому на реальных данных итог нечем заполнить — открытый вопрос 7 §11.7 и п. 39 §10;
- % непереданных документов — производное от `notTransferredDocuments / totalDocuments`,
  в БД не хранится, вычисляется при выдаче (`DynamicsService.calculatePercent`); при неизвестном
  `totalDocuments` процент не определён (null);
- `dynamicsCategory` и `previousNotTransferred` заполняются при импорте среза. Списки и сводки
  (`ClientDebtInfo`) категорию пересчитывают по текущей истории, а не читают из колонки (п. 42 §10).

| Поле | Тип | Ограничения |
|---|---|---|
| id | Long | PK, SEQUENCE `document_transfer_snapshots_seq` (шаг 50, V12) |
| client | Client | @ManyToOne LAZY, not null — FK `client_id` на `clients` |
| reportingPeriod | String | not null — свободный формат («2025 год», «1 квартал 2026») |
| snapshotDate | LocalDate | not null — дата, на которую актуальны данные |
| totalDocuments | Integer | опционален; CHECK (>= 0) |
| notTransferredDocuments | Integer | not null; CHECK (>= 0 и <= totalDocuments, когда total задан) |
| dynamicsCategory | DynamicsCategory | enum, опционален; колонка `dynamics_category varchar(32)` + CHECK по списку значений (`V11__snapshot_dynamics_and_call_list.sql`) |
| previousNotTransferred | Integer | опционален; колонка `previous_not_transferred int` — значение долга из предшествующего среза |

Индекс `idx_document_transfer_snapshots_client_period_date (client_id, reporting_period,
snapshot_date)` покрывает выборку динамики по клиенту/периоду и поиск последнего среза для
наследования total (индекс на FK создаётся явно — Postgres не строит их автоматически; то же
для `refresh_tokens.user_id`, см. выше).

### DynamicsCategory (enum)
`NO_DEBT`, `FIRST_OCCURRENCE`, `DECREASING`, `UNCHANGED_OR_GROWING`, `REAPPEARED`,
`PERSISTENT`, `NEWCOMER_RISK` (`V11`, varchar + CHECK). Детерминированный расчёт выполняет
`snapshots/DynamicsService` — дважды: при импорте среза (категория сохраняется в строку среза) и
на лету при построении списков (`WorklistService`, `ClientDebtInfo`).

Как реализовано (`classifySnapshot`): срезы клиента упорядочиваются по `(snapshotDate, id)`
**без разбивки по отчётным периодам**; «предыдущий» — соседний срез в этой последовательности,
каким бы периодом он ни был. Приоритет правил: `NO_DEBT` → `NEWCOMER_RISK` → `REAPPEARED` →
`FIRST_OCCURRENCE` → `DECREASING` → `PERSISTENT` → `UNCHANGED_OR_GROWING`; «новичок» — менее
6 месяцев между `clients.created_at` и датой среза. Отклонения от формулировок §11.2 («предыдущий
срез того же периода») и связанные с этим эффекты — п. 40 и 42 §10.

### CallListItem (`call_list_items`)
Единый закреплённый список на обзвон (пул обзвона, §11.4; `V11__snapshot_dynamics_and_call_list.sql`):
администратор выставляет фильтры по долгу, проценту непереданных и низкой динамике, предпросматривает
список и закрепляет его для всех операторов. Список один для всех; изменять и пересоставлять его
может только администратор.

| Поле | Тип | Ограничения |
|---|---|---|
| id | Long | PK, IDENTITY |
| client | Client | @ManyToOne LAZY, not null, unique (`uk_call_list_items_client`, ON DELETE CASCADE) |
| createdBy | User | @ManyToOne LAZY, not null — администратор, закрепивший список |
| createdAt | Instant | not null — момент фиксации списка |
| position | Integer | not null, default 0 — порядок отображения в пуле (индекс `idx_call_list_items_position`) |

### ClientEvent (`client_events`)
Единая история клиента (сценарии 6, 9, 10, 12 ТЗ; `V9__client_event.sql`): любая мутация
(звонок, письмо, комментарий, смена этапа, переназначение, загрузка данных, претензия,
санкция) оставляет запись с датой, временем и автором. Одна широкая таблица с типом и
nullable-полями; выдаётся хронологически от новых к старым (индекс
`idx_client_events_client_created (client_id, created_at DESC, id DESC)`).

| Поле | Тип | Ограничения |
|---|---|---|
| id | Long | PK, SEQUENCE `client_events_seq` (шаг 50, V12) |
| client | Client | @ManyToOne LAZY, not null |
| author | User | @ManyToOne LAZY, not null — автор действия |
| createdAt | Instant | not null (`@CreatedDate`), колонка `created_at` |
| type | ClientEventType | not null, varchar + CHECK |
| comment | String | text — общий для всех типов; автокомментарии системных событий на русском («Этап: X → Y», «Ответственный: A → B») |
| callResult | CallResult | только для CALL (CHECK) |
| agreement | String | text — договорённость, только для CALL |
| clientDeadline | LocalDate | срок со стороны клиента, только для CALL |
| nextContactAt | LocalDate | дата следующего контакта; CALL с этим полем обновляет `clients.next_contact_at` |
| recordUrl | String | text — ссылка на запись разговора (CALL) |

### ClientEventType (enum)
`CALL`, `LETTER_SENT`, `COMMENT` — заводятся вручную через `POST /clients/{id}/events`
(сервис отклоняет системные типы — `EVENT_TYPE_NOT_ALLOWED`); `STAGE_CHANGED`,
`ASSIGNEE_CHANGED`, `IMPORT`, `CLAIM`, `SANCTION` — только сервисным слоем
(`ClientEventService.record`) при соответствующих мутациях. Поля результата/договорённости/
сроков/ссылки разрешены только CALL (`EVENT_FIELD_NOT_ALLOWED`), результат звонка обязателен
(`CALL_RESULT_REQUIRED`); CALL с `stage` меняет этап по правилам StagePolicy и создаёт
отдельное событие STAGE_CHANGED в той же транзакции.

### CallResult (enum)
`CONNECTED`, `NO_ANSWER`, `CALLBACK_LATER`, `WRONG_NUMBER` (принято 2026-11-08, вопрос 3 §11.7;
содержательные детали — договорённость и сроки — несут поля события, а не значения перечисления).

### Claim (`claims`) и Sanction (`sanctions`)
Претензионная работа (`V10__claim_sanction.sql`, §11.4): претензии и санкции добавляет и
меняет администратор, каждая мутация дублируется событием CLAIM/SANCTION в истории клиента
(автокомментарии: «Претензия №ПР-1 зарегистрирована (статус NEW)», «…: статус NEW → SENT»,
«Санкция «приостановка» по претензии №ПР-1: статус PLANNED → APPLIED»).

Claim: id PK; client @ManyToOne not null (индекс `idx_claims_client_id`); `number`
varchar(64) not null — внешний номер, иммутабелен; `claimDate` DATE not null;
`incidentNumber` varchar(64) опционален; `receivedByClientAt` DATE опционален — дата
получения клиентом; `status` ClaimStatus not null default `NEW`; `comment` text;
`createdAt` Instant not null.

Sanction: id PK; claim @ManyToOne not null (индекс `idx_sanctions_claim_id`); `name`
varchar(128) not null — свободный текст (справочник доступных санкций не определён, §11.7);
`status` SanctionStatus not null default `PLANNED`; `createdAt` Instant not null.

### ClaimStatus / SanctionStatus (enum'ы)
ClaimStatus: `NEW`, `SENT`, `RECEIVED`, `SATISFIED`, `CANCELLED`; SanctionStatus: `PLANNED`,
`APPLIED`, `CANCELLED` (наборы приняты 2026-11-08, вопрос 3 §11.7; расширение — миграцией
CHECK-констрейнта). Хранятся varchar + CHECK, PATCH-семантика «null = не менять».

## 5. Аутентификация (JWT)

Схема stateless, без сессий (`SessionCreationPolicy.STATELESS`), CSRF отключён.

- Пароли хранятся как BCrypt-хеш в формате `DelegatingPasswordEncoder` — с префиксом
  алгоритма (`{bcrypt}$2a$…`): хеш самоописываем, алгоритм — свойство строки в БД, а не
  выбор класса; смена дефолта (например, на `{argon2}`) — смена кодировщика, без миграции
  уже записанных хешей. Хеши, записанные до внедрения префиксов (голый `$2a$…`), матчатся
  fallback-BCrypt (`setDefaultPasswordEncoderForMatches`) — миграция данных не нужна.
- Access-токен: подпись HS256 (HMAC-SHA256, ключ из `jwt.secret`; алгоритм закреплён явно
  и в выдаче, и в декодере — совпадение обязательно), subject = `user.getId()`
  (username изменяем — идентификация в токене не должна ломаться при переименовании),
  claims: `preferred_username` (бывший subject, для отображения/фронта), `email`, `roles`,
  `jti` (UUID, уникален у каждого токена — задел под серверный блок-лист); срок —
  `jwt.access-expiration` (15 мин). Токены, выданные до смены subject (раньше username),
  после выката невалидны: нечисловой subject → `InvalidBearerTokenException` в конвертере → 401;
  клиент обновляет пару через `/refresh` (refresh-токены от схемы access-токена не зависят).
- Refresh-токен: сырой UUID отдаётся клиенту, в БД хранится только его SHA-256-хеш,
  поиск — по хешу; срок — `jwt.refresh-expiration` (7 дней).
- Проверка access-токена — resource server Spring Security (п. 23 §10, заменён рукописный
  `JwtAuthenticationFilter`): `BearerTokenAuthenticationFilter` → `NimbusJwtDecoder`
  (бин в `SecurityConfig`: валидация HMAC-подписи и срока, один парсинг на запрос) →
  `JwtAuthenticationConverter` (`security/`): subject → id, пользователь загружается
  `CustomUserDetailsService.loadUserById` через Caffeine-кэш (TTL 30 с, максимум 10 000 —
  БД выведена из горячего пути, п. 20 §10; удаление пользователя и смена роли подхватываются
  в пределах TTL), итог — `UserAuthenticationToken` с principal `CustomUserDetails`
  (контроллеры и сервисы не зависят от типа токена ресурсного сервера). jjwt остаётся только
  на выдачу (`JwtService`). Любая неудача — испорченный/истёкший токен, нечисловой subject,
  неизвестный пользователь — `AuthenticationException` → 401 (не 500).
- `RestAuthenticationEntryPoint` отдаёт единый контракт ошибок неаутентифицированным запросам:
  **401** с телом `ErrorResponse` (JSON) вместо дефолтного 403. Подключён и в
  `exceptionHandling`, и в `oauth2ResourceServer`: ошибки самого bearer-фильтра идут через
  его failure handler, а не через exceptionHandling. Причина 401 (нет токена, плохая подпись,
  истёк, неизвестный пользователь) — в debug-лог, ответ клиенту одинаков и причину не раскрывает.
  Заголовок `WWW-Authenticate` при 401 не ставится. Отказ по правам (`@PreAuthorize` → 403)
  идёт мимо единого контракта — с пустым телом (п. 43 §10, §6).
- CORS: бин `CorsConfigurationSource` + `.cors(...)` в цепочке; источники — свойство
  `app.cors.allowed-origins` (список через запятую; в dev — `http://localhost:5173`, переопределяется
  переменной окружения `APP_CORS_ALLOWED_ORIGINS`). Разрешены методы GET/POST/PUT/PATCH/DELETE/OPTIONS
  и заголовки `Authorization`, `Content-Type`; credentials не включены (токен — в заголовке). Пустой
  список (по умолчанию) — кросс-доменные запросы не разрешены, same-origin работает без ограничений.
- Публичные маршруты: `/api/v1/auth/**` — кроме `/api/v1/auth/logout` (матчер `authenticated()`
  в `SecurityConfig` стоит раньше permitAll), и служебный `/error`; всё остальное — только
  аутентифицированным. `POST /api/v1/auth/register` публичен: любой, кому доступен API, может
  создать себе аккаунт `OPERATOR` (политика регистрации — п. 45 §10).
- Включена method security (`@EnableMethodSecurity`) — можно использовать `@PreAuthorize` по ролям.
- `CustomUserDetailsService` возвращает `CustomUserDetails` — record-обёртку над сущностью `User`
  (роль → authority `ROLE_<ROLE>`); она же — principal в `SecurityContext` после конвертера и в
  результате `authenticate(...)`.

Сценарии (реализованы в `AuthService`; `register`/`login`/`logout` — `@Transactional`, `refresh` — нет,
см. ниже):
- `register` — проверка уникальности username и email (нормализация — в компакт-конструкторе
  `RegisterRequest` до Bean Validation: email — `trim` + blank → null (п. 16 §10, то же —
  `contactEmail` в `ClientCreateRequest`/`ClientUpdateRequest`, blank = «данных нет»/«не менять»);
  username и email — к нижнему регистру через `common/UserIdentityNormalizer` (п. 30 §10):
  в БД пишутся уже приведённые значения, уникальность — функциональные unique-индексы по
  `lower(...)`), → сохранение (BCrypt, роль OPERATOR) → выдача пары токенов. Конкурентный
  конфликт уникальности в БД ловится `GlobalExceptionHandler` как 409 — с точным кодом по имени
  констрейнта/индекса (§6).
- `login` — username нормализуется к нижнему регистру в `LoginRequest` (совпадает с записями в
  БД: вход не чувствителен к регистру, `Admin`/`admin` — один аккаунт) — далее через
  `authenticationManager.authenticate(...)` (бин в `SecurityConfig`;
  `DaoAuthenticationProvider` собирается из бинов `CustomUserDetailsService` + `PasswordEncoder`):
  любой `AuthenticationException` → единый 401 "Invalid credentials". Тайминг-атака на
  перечисление usernames закрыта самим провайдером: при `UsernameNotFoundException` он выполняет
  холостую bcrypt-сверку (`mitigateAgainstTimingAttack`), время ответа не выдаёт существование
  username. Сущность для выдачи токенов достаётся из principal (`CustomUserDetails.user()`).
  Любая неудача фиксируется счётчиком антибрутфорса (см. ниже), успех — сбрасывает его.
- `refresh` — ротация одним методом `RefreshTokenService.rotate(rawToken)` в одной транзакции:
  чтение по хешу под `@Lock(PESSIMISTIC_WRITE)` (SELECT … FOR UPDATE — конкурентные `/refresh`
  с одним токеном сериализуются: двух новых пар токенов не выдаётся, второй запрос детерминированно
  видит ротированный токен и попадает в reuse-detection, семейство отзывается вместе со свежим
  токеном победителя — клиент перелогинивается). FOR UPDATE — только на строку `refresh_tokens`:
  поиск без JOIN FETCH (пользователь инициализируется в транзакции через `Hibernate.unproxy`,
  OSIV выключен), иначе блокировка строки `users` вместе с `deleteByUser` создавала бы дедлок при
  параллельном refresh двух разных токенов одного пользователя. Неизвестный и просроченный токен →
  401 (просроченный удаляется), повторное предъявление ротированного токена (reuse-detection,
  паттерн token family) → отзыв всех токенов пользователя (`deleteByUser`) и 401; активный токен
  помечается `rotated_date` и заменяется новым. `rotate` возвращает `Optional` и не бросает
  исключение изнутри транзакции, а `AuthService.refresh` намеренно без `@Transactional` — иначе
  откат транзакции по 401 отменял бы уже сделанный отзыв семейства.
- `logout` — `POST /api/v1/auth/logout`, требует access-токен (иначе 401): пользователь берётся из
  principal (`@AuthenticationPrincipal CustomUserDetails`), отзываются **все** refresh-токены
  пользователя (`RefreshTokenService.revokeAllForUser` → `deleteByUser`) — logout закрывает все
  устройства/сессии, ответ 204. Выданный access-токен остаётся технически валиден до истечения
  срока (≤ 15 мин): схема stateless, серверного блок-листа токенов нет, хотя `jti` для него
  уже заложен (см. выше) — это осознанный компромисс (потребовалось бы общее состояние).

Антибрутфорс (п. 12 §10, закрыт 2026-10-28) — два независимых механизма:

1. **Rate limiting `POST /api/v1/auth/login|register`.** `AuthRateLimiter` (`security/`) вызывается
   из `AuthController` до сервиса: token bucket (Bucket4j) на пару username+IP (IP — первый адрес
   `X-Forwarded-For`, при отсутствии заголовка `remoteAddr`; username — уже нормализованный из
   `LoginRequest`/`RegisterRequest`). Переполнение →
   `DomainException(TOO_MANY_AUTH_ATTEMPTS)` → **429** с телом `ErrorResponse`. Бакеты in-memory — Caffeine-кэш
   (`maximumSize` 100 000, `expireAfterAccess` 1 ч; вытеснение простаивающих ключей предохраняет
   карту от роста под скриптовым перебором username). Состояние локально для инстанса: при
   масштабировании на несколько инстансов каждый считает лимит независимо — общий store
   (например, Redis) при необходимости позже. Ограничение по паре username+IP не сдерживает
   массовую регистрацию/перебор с ротацией username с одного IP — при необходимости добавляется
   отдельный IP-only бакет.
2. **Блокировка аккаунта.** `users.failed_attempts` / `users.locked_until` (миграция
   `V6__user_account_lockout.sql`). Любая неудачная попытка входа (в том числе повторной попыткой
   заблокированного аккаунта — блокировка продлевается) инкрементит счётчик; при достижении
   порога (`app.security.account-lockout.max-failed-attempts`, 5) ставится
   `locked_until = now + lock-duration` (15 мин). Успешный вход сбрасывает счётчик и блокировку.
   Учёт при загрузке: `CustomUserDetails.isAccountNonLocked()` — `DaoAuthenticationProvider`
   отклоняет заблокированный аккаунт до сверки пароля (`LockedException`), ответ — тот же единый
   401 "Invalid credentials", причина не раскрывается. Инкремент выполняет
   `AccountLockoutService.registerFailedLogin` атомарным bulk-UPDATE
   (`UserRepository.registerFailedLogin`: `failedAttempts + 1` и `lockedUntil` по `case` при
   достижении порога — одним UPDATE на уровне строки, п. 31 §10; read-modify-write над сущностью
   терял инкременты при параллельных неудачных попытках) в отдельной транзакции (`REQUIRES_NEW`):
   внешний `login` помечен `@Transactional` и откатывается по 401 — без отдельной транзакции
   откат стирал бы инкремент (тот же принцип, что у refresh-ротации: мутация-последствие живёт
   вне откатывающейся транзакции, см. `refresh` выше). Сброс при успехе — симметричный условный
   bulk-UPDATE (`resetFailedLogins`: чистый аккаунт не затрагивается).

Очистка: просроченные токены (в том числе уже ротированные) удаляет `RefreshTokenCleanupJob`
(`auth/`, `@EnableScheduling` на `KcrBackendApplication`) — `@Scheduled`-cron
`app.cleanup.refresh-token-cron` (по умолчанию `0 0 4 * * *`, локальная зона сервера) вызывает
`RefreshTokenService.deleteExpired()` → `deleteByExpiryDateBefore(now)`; без job таблица
`refresh_tokens` росла бы бесконечно.

Bootstrap первого администратора (п. 27 §10, закрыт 2026-11-02) — `AdminBootstrap`
(`users/`, `ApplicationRunner`). Без него после чистого деплоя административный API мёртв:
регистрация всегда создаёт OPERATOR, а создание/правка клиентов защищены `hasRole('ADMIN')`.
Каждый старт: если пользователей с ролью ADMIN нет (`existsByRole`), учётная запись создаётся
из `ADMIN_USERNAME`/`ADMIN_PASSWORD` (username — через `UserIdentityNormalizer` к нижнему
регистру, как все записи `users`; пароль — тем же `PasswordEncoder`, роль ADMIN, email NULL);
есть хоть один ADMIN — no-op (идемпотентно). Учётные данные не заданы: prod — fail-fast при
старте (чистый деплой без возможности управления не должен подниматься), dev — WARN и
продолжение (значения — из `.env`, см. §7). Занятый username (например, OPERATOR с тем же
именем — exists-проверка по нормализованному значению) — ошибка конфигурации, fail-fast в
обоих профилях. Для эксплуатации — одноразовая команда `--create-admin`: создаёт учётную
запись из тех же переменных независимо от существующих ADMIN (добавление администраторов),
после чего приложение завершается (`SpringApplication.exit`; при работающем инстансе
запускать с `--server.port=0`, см. §8).

**kcr-ml и токены.** Сервис ИИ-помощника (§1) проверяет те же access-токены локально — HS256,
общий секрет (`jwt.secret` бэкенда = `AUTH_JWT_SECRET` kcr-ml) — и с каждым GET-запросом к бэкенду
пробрасывает токен как есть: права на данные считает бэкенд, дублирования правил в kcr-ml нет.
От браузера kcr-ml получает токен WebSocket-запросом в query-параметре (п. 47 §10). Токен
удалённого или заблокированного пользователя остаётся валидным по подписи и сроку до истечения
access-токена (≤ 15 мин), но бэкенд отвечает на него 401 — то же окно, что и после logout.

## 6. Исключения и контракт ошибок

Единая точка правды об ошибках API — enum `ErrorCode` (пакет `common/error/`): каждый код несёт
HTTP-статус и сообщение по умолчанию, имя константы — машиночитаемый код из ответа. Сервисы
бросают единственное доменное исключение `DomainException(ErrorCode)`; отдельный класс на каждый
статус не заводится (п. 14 §10, 2026-10-30: прежняя иерархия `BasicException`/`HttpException` +
6 предметных классов дублировала `HttpStatus` и ломала семантику is-a — наследник
`BadRequestException` отвечал 409/401/404).

`GlobalExceptionHandler` (`@RestControllerAdvice`) маппит `DomainException` и ошибки фреймворка
в единый `ErrorResponse`:

```json
{ "code": "CLIENT_NOT_FOUND", "message": "Client not found", "timestamp": "2026-10-30T12:00:00Z" }
```

- `code` — машиночитаемый код для фронтенда; `timestamp` — ISO-8601 (`Instant`), не epoch-millis;
  `message` — человекочитаемое пояснение (для ошибок валидации — сводка по полям).
- Реестр кодов (все — значения `ErrorCode`):

| Код | HTTP | Источник |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | `RestAuthenticationEntryPoint` (единый контракт 401, §5) |
| `INVALID_CREDENTIALS` | 401 | login (провайдер вернул `AuthenticationException`) |
| `INVALID_REFRESH_TOKEN` | 401 | refresh (неизвестный/просроченный/ротированный токен) |
| `USERNAME_ALREADY_TAKEN` / `EMAIL_ALREADY_TAKEN` | 409 | register: exists-проверка и гонка в БД (констрейнт) |
| `TOO_MANY_AUTH_ATTEMPTS` | 429 | `AuthRateLimiter` (антибрутфорс, §5) |
| `CLIENT_NOT_FOUND` | 404 | карточка/срезы/PATCH клиента |
| `CLIENT_ALREADY_EXISTS` | 409 | POST /clients: exists-проверка и гонка в БД (констрейнт) |
| `BLANK_ORGANIZATION_NAME` | 400 | PATCH /clients (пустое название) |
| `INVALID_SORT_FIELD` / `INVALID_SORT_DIRECTION` | 400 | GET /clients (whitelist сортировки) |
| `IMPORT_FILE_REQUIRED` | 400 | импорт Excel: часть `file` отсутствует в запросе либо пуста (§11.4) |
| `IMPORT_FILE_UNREADABLE` | 400 | импорт Excel: файл не разбирается как книга Excel (недоверенный ввод; исключение парсера проглатывается — это клиентская ошибка, не внутренняя) |
| `IMPORT_PERIOD_NOT_FOUND` | 400 | импорт срезов: ни один лист файла не совпал с выбранным отчётным периодом (фильтр по периоду, §9) |
| `CLIENT_ALREADY_TAKEN` | 409 | `POST /clients/{id}/take`: клиент уже занят другим ответственным |
| `USER_NOT_FOUND` | 404 | `PATCH /clients/{id}/assignment`: назначаемый пользователь не существует |
| `STAGE_CHANGE_FORBIDDEN` | 403 | смена этапа оператором вне своих клиентов / вне рабочих этапов (`StagePolicy`) |
| `EVENT_TYPE_NOT_ALLOWED` | 400 | ручное создание системного типа события |
| `EVENT_FIELD_NOT_ALLOWED` | 400 | поля звонка (`callResult`, `agreement`, `clientDeadline`, `nextContactAt`, `recordUrl`, `stage`) у не-CALL события |
| `CALL_RESULT_REQUIRED` | 400 | событие `CALL` без результата звонка |
| `CLAIM_NOT_FOUND` / `SANCTION_NOT_FOUND` | 404 | претензия/санкция по id не существует |
| `VALIDATION_FAILED` | 400 | `MethodArgumentNotValidException` (Bean Validation DTO), `HandlerMethodValidationException` (встроенная method-validation параметров контроллера — пагинация, п. 28 §10) и `MaxUploadSizeExceededException` (файл импорта больше лимита multipart, §7) |
| `MALFORMED_REQUEST_BODY` | 400 | `HttpMessageNotReadableException` (невалидный JSON) |
| `INVALID_REQUEST_PARAMETER` | 400 | `MethodArgumentTypeMismatchException` (query/path-параметр) и `MissingServletRequestParameterException` (отсутствующий обязательный параметр; иначе попадал бы в catch-all п. 32 §10 как 500) |
| `RESOURCE_NOT_FOUND` | 404 | `NoResourceFoundException` (несуществующий маршрут) |
| `DATA_CONFLICT` | 409 | нарушение целостности БД по неизвестному констрейнту |
| `SHA256_UNAVAILABLE` | 500 | JRE без SHA-256 (недостижимо на стандартном JRE) |
| `INTERNAL_ERROR` | 500 | catch-all `@ExceptionHandler(Exception.class)` — непредвиденное исключение (п. 32 §10); причина — только в лог, ответ нейтрален. Отказ авторизации (`AccessDeniedException` от `@PreAuthorize`) в catch-all не попадает: отдельный хендлер перебрасывает исключение в security-фильтр — 403 решается `ExceptionTranslationFilter` |

- `DataIntegrityViolationException` (гонка «exists-проверка + save» при конкурентной записи)
  различается по имени нарушенного констрейнта/индекса: Hibernate
  `ConstraintViolationException#getConstraintName`, при отсутствии обёртки в цепочке причин —
  парсинг сообщения драйвера; `uk_users_username`/`idx_users_username_lower` →
  `USERNAME_ALREADY_TAKEN`, `uk_users_email`/`idx_users_email_lower` → `EMAIL_ALREADY_TAKEN`,
  `uk_clients_kcr_code` → `CLIENT_ALREADY_EXISTS`, прочее/неизвестное → `DATA_CONFLICT`
  (всё — 409; `uk_*` — констрейнты V1/V3, `idx_*_lower` — функциональные unique-индексы
  V7, п. 30 §10; до п. 14 любое нарушение отвечало «Username or email already taken»).
- Сервисы бросают только `DomainException`; маппинг в HTTP — исключительная зона ответственности
  хендлера. Неаутентифицированным `RestAuthenticationEntryPoint` отдаёт тот же контракт
  (`AUTHENTICATION_REQUIRED`).

Известные пробелы контракта (проверено на работающем приложении 2026-11-28; устранение — п. 43 §10):

| Ситуация | Фактический ответ | Ожидаемый |
|---|---|---|
| Недостаточно прав (`@PreAuthorize`) | 403 с **пустым телом** — `AccessDeniedException` перебрасывается в security-фильтр, у которого нет своего `accessDeniedHandler` | 403 `ACCESS_DENIED` в теле `ErrorResponse` |
| Неподдерживаемый метод (напр. `DELETE /users`) | **500** `INTERNAL_ERROR` + запись ERROR в лог | 405 |
| Неподдерживаемый `Content-Type` (напр. `text/plain` в JSON-эндпоинт) | **500** `INTERNAL_ERROR` | 415 |
| В multipart-запросе импорта нет части `file` | **500** `INTERNAL_ERROR` (проверка `file.isEmpty()` в сервисе не успевает) | 400 `IMPORT_FILE_REQUIRED` |

Причина общая: catch-all `@ExceptionHandler(Exception.class)` перехватывает исключения
Spring MVC, у которых нет собственного хендлера, — они должны были бы разрешаться
`DefaultHandlerExceptionResolver` в 4xx. Тот же 403, полученный через `DomainException`
(`STAGE_CHANGE_FORBIDDEN`), отдаётся в едином контракте корректно.

## 7. Конфигурация

Конфигурация — `src/main/resources/application.yaml` с профилями `dev`/`prod`
(2026-10-26, закрыт п. 5 §10 — секреты вынесены из VCS):

- `application.yaml` — общая часть: имя приложения; `spring.profiles.default=dev` (без явного
  профиля — `bootRun`, тесты — активен `dev`); JPA: `ddl-auto=validate`, OSIV выключен, батчинг
  `hibernate.jdbc.batch_size=50`, `order_inserts`, `order_updates` (INSERT/UPDATE уходят пакетами —
  вместе с sequence-генерацией id, V12); лимиты multipart: `max-file-size=10MB`,
  `max-request-size=15MB`; сроки токенов (`jwt.*`); секция `app.*` (см. ниже).
- `application-dev.yaml` — локальная БД compose-стенда: `jdbc:postgresql://localhost:5433/kcr`,
  пользователь `kcr` (`DB_USERNAME` опциональна), `jwt.secret` из `JWT_SECRET`, CORS-origins
  `http://localhost:5173`; подключает `.env` (`spring.config.import`, см. ниже). С 2026-11-08 БД —
  контейнер `kcr-db-1` единого корневого `docker-compose.yml` (прежде — `kcr-backend-db-1`, ещё
  раньше — контейнер `postgres` на 5432).
- `application-prod.yaml` — подключение (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) и
  `JWT_SECRET` строго из окружения, значений по умолчанию нет: при незаданных переменных
  приложение не стартует (fail-fast). Батчинг и лимиты multipart — общие, действуют и в prod.

Секреты в репозитории не хранятся и подставляются плейсхолдерами из переменных окружения:

| Переменная | Профили | Назначение |
|---|---|---|
| `DB_PASSWORD` | dev, prod | пароль БД |
| `JWT_SECRET` | dev, prod | HMAC-ключ для подписи JWT, ≥ 32 байт |
| `DB_URL` | prod | JDBC-URL БД |
| `DB_USERNAME` | prod; в dev опциональна (по умолчанию `kcr`) | пользователь БД |
| `ADMIN_USERNAME` | dev опциональна, prod — из окружения | username первого администратора (bootstrap при старте / `--create-admin`, см. §5); при чистом деплое в prod без учётных данных — fail-fast |
| `ADMIN_PASSWORD` | dev опциональна, prod — из окружения | пароль первого администратора (см. §5) |
| `APP_CORS_ALLOWED_ORIGINS` | опциональна | CORS-origins через запятую; в dev задана в `application-dev.yaml`, в compose подставляется из `.env` (по умолчанию `http://localhost:5173`) |

`JWT_SECRET` общий для бэкенда и kcr-ml (там он называется `AUTH_JWT_SECRET`); короче 32 байт
бэкенд не примет — `Keys.hmacShaKeyFor` падает на старте.

Прочие прикладные свойства: `app.cors.allowed-origins` (список через запятую; dev —
`http://localhost:5173`, переопределяется окружением `APP_CORS_ALLOWED_ORIGINS`, см. §5),
`app.cleanup.refresh-token-cron` (cron суточной очистки refresh-токенов, общая часть
`application.yaml`, по умолчанию `0 0 4 * * *`, см. §5), учётные данные bootstrap первого
администратора `app.admin.username`/`app.admin.password` (пустые по умолчанию, семантика
применения — в `AdminBootstrap`, см. §5) и параметры антибрутфорса (§5):
`app.security.auth-rate-limit.capacity` (10) с `refill-duration` (1m) и
`app.security.account-lockout.max-failed-attempts` (5) с `lock-duration` (15m).

Для локальной разработки секреты кладутся в `server/kcr-backend/.env` (копия шаблона
`.env.example`; сам `.env` в `.gitignore`). Профиль `dev` подключает его через
`spring.config.import=optional:file:.env[.properties]` — это properties-файл вида
`KEY=VALUE`, читаемый относительно рабочей директории (`bootRun` и тесты запускаются
из каталога модуля). Переменные окружения приоритетнее значений из `.env`. Для контейнерного
стенда те же значения задаёт **корневой** `.env` (шаблон — корневой `.env.example`), их
подставляет `docker-compose.yml`; в контейнере всегда `SPRING_PROFILES_ACTIVE=prod`.
Часовой пояс контейнера — UTC: cron очистки токенов `0 0 4 * * *` срабатывает в 07:00 МСК,
а «сегодня» считается по дате UTC (п. 54 §10).

Схема БД управляется Flyway: SQL-миграции в `src/main/resources/db/migration` (`V1`–`V12`,
перечень ниже) применяются при старте приложения до
инициализации JPA; история — в таблице `flyway_schema_history`. В Boot 4 автоконфигурация Flyway
вынесена в отдельный модуль `spring-boot-flyway` (зависимость вместо голого `flyway-core`,
выявлено 2026-10-26: без него миграции молча не применяются и `ddl-auto=validate` падает по
«missing table»). Каталог миграций — дефолтный (`classpath:db/migration`), явно не задаётся.

| Миграция | Что делает |
|---|---|
| `V1__init.sql` | `users`, `refresh_tokens` (baseline 2026-10-25) |
| `V2__refresh_token_reuse_detection.sql` | `refresh_tokens.rotated_date` (reuse-detection, §5) |
| `V3__client.sql` | `clients` |
| `V4__document_transfer_snapshot.sql` | `document_transfer_snapshots` + индекс client/period/date |
| `V5__refresh_tokens_user_id_index.sql` | индекс `refresh_tokens.user_id` |
| `V6__user_account_lockout.sql` | `users.failed_attempts`, `users.locked_until` |
| `V7__users_case_insensitive_identity.sql` | unique-индексы `lower(username)` / `lower(email)` вместо `uk_users_*` |
| `V8__client_work_fields.sql` | `clients.stage` / `assignee_id` / `next_contact_at` / `created_at` + индексы |
| `V9__client_event.sql` | `client_events` + индекс истории |
| `V10__claim_sanction.sql` | `claims`, `sanctions` + индексы |
| `V11__snapshot_dynamics_and_call_list.sql` | `dynamics_category`, `previous_not_transferred`; `call_list_items` |
| `V12__batch_sequences.sql` | sequence-ы `clients_seq`, `client_events_seq`, `document_transfer_snapshots_seq` (шаг 50) для батчинга |

Файлы миграций после применения не редактируются — любое изменение схемы в новую `V<n>__*.sql`
(в том числе выправление V12, п. 38 §10).

## 8. Сборка и запуск

Предусловия: JDK 17 (toolchain — Gradle сам не скачивает, нужна установленная), Docker с
запущенным контейнером `kcr-db-1` (`docker start kcr-db-1` из корня репозитория; создаётся
`docker compose up -d db`) и файл `server/kcr-backend/.env` с `DB_PASSWORD`, `JWT_SECRET`
(шаблон — `.env.example`). Интеграционные тесты идут в эту же базу и применяют к ней миграции.

```bash
# из каталога server/kcr-backend
./gradlew build          # сборка + все тесты (нужен PostgreSQL, см. выше)
./gradlew test           # только тесты
./gradlew bootRun        # запуск приложения, http://localhost:8080 (профиль dev, БД localhost:5433)

# одноразовое создание администратора из ADMIN_USERNAME/ADMIN_PASSWORD (см. §5);
# приложение завершается после создания; при работающем инстансе добавить
# --server.port=0, чтобы не делить порт
./gradlew bootRun --args='--create-admin'
java -jar build/libs/kcr-backend-0.0.1-SNAPSHOT.jar --create-admin
```

Отчёт о тестах: `build/reports/tests/test/index.html` (число тестов по классам — §9).

Контейнерный стенд (весь монорепо, из корня репозитория; настройки — корневой `.env`):

```bash
docker compose up -d --build     # db, app (:8080), ml (:8000), frontend (:5173)
docker compose logs -f app
docker compose down              # остановить; down -v — вместе с данными БД
```

`app` собирается из `Dockerfile` (multi-stage, non-root) с профилем `prod`; в контейнере
приложение работает в UTC. Образ иммутабелен: после переключения веток или мержа с изменениями
бэкенда контейнер нужно пересобрать (`docker compose up -d --build app`).

## 9. Текущее состояние (актуально на 2026-11-28)

### Сводка

Что реализовано (все ссылки — на разделы выше):

- аутентификация: register/login/refresh/logout, ротация refresh-токенов с reuse-detection,
  антибрутфорс (rate limiting + блокировка аккаунта), bootstrap администратора (§5);
- клиенты: CRUD, поиск и фильтры, атомарное взятие, переназначение, этапы (§4, §11.4);
- Excel-импорт клиентов и срезов: предпросмотр/применение, фильтр листов по отчётному периоду,
  наследование итога, батчевая запись (§3, §11.4);
- операторский цикл: единая история клиента, ручные события, «сегодня нужно», претензии и санкции;
- динамика: 7 категорий, сохранение в срезе, сводка `ClientDebtInfo`;
- единый закреплённый список на обзвон: preview / generate / call-list / clear;
- фронтенд `client/kcr-frontend` и сервис ИИ-помощника `ml/` — см. §1.

Состояние проверок на 2026-11-28:

| Проверка | Результат |
|---|---|
| `./gradlew test` | **157 тестов: 149 прошли, 8 упали** — все падения в интеграционных тестах (`ClientApiIntegrationTest` 3, `OperatorCycleIntegrationTest` 2, `ClaimApiIntegrationTest` 1, `ImportApiIntegrationTest` 2), причина одна — п. 38 §10 (V12: второй генератор id). Юнит-тесты и парсеры зелёные |
| `uv run pytest` (ml) | 70/70; `ruff check` и `ruff format --check` чистые |
| `npm run build` (frontend) | собирается (Vite 6, ~273 kB JS / gzip ~83 kB) |
| Живой прогон контракта ошибок | подтверждены пробелы из §6 (п. 43 §10) |

Известные проблемы и требуемые изменения — §10 (открыты пп. 18, 19, 21, 22, 24–26, 33–37
и новые 38–63). Остаток плана ТЗ — §11.8.

### Хронология работ

Записи до 2026-11-08 сделаны до перехода на package-by-feature и называют **старые имена пакетов**
(слои `controller/`/`service/`/`dto/`/`model/`/`repository/`, а также `exception/`, `scheduling/`,
`bootstrap/`); текущее расположение классов — §3.

Ранний период (2026-10-11 … 2026-10-26):

- каркас приложения: SecurityConfig, JWT-инфраструктура, сущности `User`, `RefreshToken`, `Role`,
  `AuthController`, `AuthService`, `RefreshTokenService`, ранняя иерархия исключений и
  `GlobalExceptionHandler`; первые тесты `AuthServiceTest`, `RefreshTokenServiceTest`,
  `JwtServiceTest`, `AuthFlowIntegrationTest`, `KcrBackendApplicationTests` (позже многократно
  перерабатывались — см. следующие записи);
- Flyway (2026-10-25, закрыт п. 2 §10): миграции `db/migration/V1__init.sql` (базовая схема) и
  `V2__refresh_token_reuse_detection.sql` (колонка `rotated_date`, п. 3 §10), `ddl-auto=validate`.
  Dev-БД очищена от старой схемы (включая легаси-таблицы `person`, `role`, `user_roles`,
  `repository`) и создана заново применением V1+V2;
- п. 3 §10 закрыт (2026-10-26, хеширование refresh-токенов + reuse-detection): в БД хранится
  только SHA-256 от сырого UUID, клиенту отдаётся сырой токен, поиск по хешу; ротированный токен
  помечается `rotated_date`, его повторное предъявление отзывает всё семейство токенов
  пользователя; контракт `RefreshTokenService` переработан (`createRefreshToken(User)` +
  `rotate(rawToken) → Optional<RotationResult>`, см. §5); `./gradlew build` снова проходит
  (19 тестов);
- документация и `.gitignore` — в git (2026-10-26, закрыт п. 4 §10): этот файл закоммичен,
  `.gitignore` больше не игнорирует сам себя;
- п. 5 §10 закрыт (2026-10-26, секреты вне VCS + профили конфигурации): конфигурация
  переведена с `application.properties` на `application.yaml` + профили `application-dev.yaml`
  / `application-prod.yaml` (`spring.profiles.default=dev`); `DB_PASSWORD`, `JWT_SECRET`,
  `DB_URL`, `DB_USERNAME` разрешаются только из окружения (в prod — без дефолтов, fail-fast);
  для dev секреты — в `.env` вне git (шаблон `.env.example`, подключение через
  `spring.config.import=optional:file:.env[.properties]`, см. §7); избыточный
  `spring.datasource.driver-class-name` удалён (выводится из URL); `./gradlew build`
  проходит (19 тестов).
- пп. 6 и 17 §10 закрыты (2026-10-27, login через `AuthenticationManager`, PR #3 из
  `feature/auth`): `AuthService.login` переведён с ручного `findByUsername` + `matches` на
  `authenticationManager.authenticate(...)`; `DaoAuthenticationProvider` собирается из бинов
  `CustomUserDetailsService` + `PasswordEncoder`, любой `AuthenticationException` → единый 401,
  а при неизвестном пользователе провайдер сам выполняет холостую bcrypt-сверку — время ответа
  больше не выдаёт существование username (тайминг-атака закрыта); бин `AuthenticationManager`
  из `SecurityConfig` теперь используется; `CustomUserDetailsService` возвращает record
  `CustomUserDetails` с сущностью `User` внутри (principal); `./gradlew build` проходит
  (19 тестов).
- первая предметная сущность `Client` — «Клиент» (2026-10-27, ветка `feature/client`, по спецификации
  полей): миграция `V3__client.sql` (таблица `clients`, обязательные код KCR (unique) / ИНН / название
  организации, опциональные КПП, внутренний ID/ЛС, тип клиента (enum `ClientType`, varchar + CHECK),
  `kcr_active`/`kcr_blocked` (nullable boolean: null = нет данных), тариф, время от ЕкБ, менеджер,
  спец. признаки `flags varchar(255)[]`, контактное лицо/телефон/email), сущность `Client` +
  `ClientRepository` (`findByKcrCode`, `existsByKcrCode`); пустая заготовка `Person` удалена (часть
  п. 24 §10).
- сущность «Срез состояния передачи документов» (2026-10-27, ветка `feature/client`, по спецификации):
  миграция `V4__document_transfer_snapshot.sql` (таблица `document_transfer_snapshots`: FK на
  `clients`, отчётный период varchar, дата среза DATE, nullable `total_documents` — при повторной
  загрузке без «Всего» наследуется из последнего среза периода при импорте (сервисный слой),
  `not_transferred_documents` NOT NULL + CHECK согласованности счётчиков, составной индекс
  client+period+date), сущность `DocumentTransferSnapshot` + `DocumentTransferSnapshotRepository`
  (динамика по клиенту / клиенту и периоду, последний срез периода); срезы не перезаписываются —
  всегда новая строка; % непереданных в БД не хранится, вычисляется при выдаче.
- зафиксированы требования из ТЗ заказчика и целевой план домена (§11, 2026-10-27): границы MVP,
  бизнес-правила (порог 5%, категории динамики, этапы работы), целевая модель данных, план REST
  API и порядок внедрения.
- HTTP-предпосылки из §11.8 закрыты (2026-10-27, ветка `feature/client`): п. 7 §10 —
  `RestAuthenticationEntryPoint` (единый 401 с телом `ErrorResponse` вместо дефолтного 403);
  п. 22 §10 в части префикса — `/api/v1/**`, `/api/auth` перенесён в `/api/v1/auth`;
  п. 11 §10 — CORS (`CorsConfigurationSource` + свойство `app.cors.allowed-origins`, см. §5).
  Аудит-поля из п. 24 остаются шагу «Операторский цикл» (V5).
- REST CRUD клиентов — шаг 1 §11.8 (2026-10-27, ветка `feature/client`): `GET /api/v1/clients` —
  страница с поиском по коду KCR/ИНН (без учёта регистра) и фильтрами `clientType`/`kcrActive`/
  `kcrBlocked`/`flag` (членство в `flags` через `array_contains`), пагинация с сортировкой по
  whitelist-полям; JPA Specifications (`ClientRepository` расширен `JpaSpecificationExecutor`);
  `GET /api/v1/clients/{id}` — карточка; `GET /api/v1/clients/{id}/snapshots` — история срезов
  по датам (опционально по отчётному периоду) с вычисляемым `percentNotTransferred`;
  `POST /api/v1/clients` — ручное создание карточки (только ADMIN; обязательны код KCR, ИНН,
  название организации; дубль кода KCR → `ClientAlreadyExistsException` → 409; дополняет
  Excel-импорт — upsert импорта обновит созданную запись по коду KCR);
  `PATCH /api/v1/clients/{id}` — только ADMIN (`@PreAuthorize`), семантика «null = не менять»,
  `kcrCode` неизменяем (в DTO отсутствует). DTO: `ClientCreateRequest`, `ClientUpdateRequest`,
  `ClientResponse`, `SnapshotResponse`, `PageResponse` (стабильный контракт страницы вместо
  нестабильной сериализации `Page` Spring Data); `ClientNotFoundException` → 404;
  `MethodArgumentTypeMismatchException` → 400 в `GlobalExceptionHandler`. Колонка `flags` в V3
  переведена с `text[]` на `varchar(255)[]` до коммита ветки: Hibernate рендерит `array_contains`
  как `flags @> cast(array[?] as varchar array)`, а для `text[]` Postgres не подбирает оператор
  `@>`; dev-схема пересоздана применением миграций заново. Тесты: `ClientServiceTest` (12,
  Mockito), `ClientApiIntegrationTest` (2 e2e-сценария: 401-контракт, поиск/фильтры/флаги,
  снапшоты, PATCH и POST по ролям, валидация, 409 на дубль кода; требует PostgreSQL);
  `./gradlew build` проходит (33 теста).
- слой парсинга Excel-импорта — адаптеры реальных выгрузок (2026-10-27, ветка `feature/client`,
  по двум файлам от заказчика: «КЦР_последняя выгрузка_Дубна.xlsx» и «Срез 20.10.26_Дубна.xlsx»):
  зависимость Apache POI 5.5.1; пакет `imports` — `ClientExcelParser` (клиенты) и
  `SnapshotExcelParser` (срезы) выдают командные DTO `ClientUpsertCommand`/`SnapshotCreateCommand`
  (контракт §11.6 — источник данных заменяем на API без изменения потребителя) и `ParseResult`
  (команды + `ParseIssue` с листом/строкой/колонкой/причиной; строки с ошибками в команды не
  попадают). Толерантность к реальным файлам: заголовок ищется в первых 10 строках по
  нормализованным алиасам (регистр, ё, пробелы, включая опечатки вида «Наитменование»,
  «Уникальны обезличинный»); колонка кода KCR — по алиасам с фолбэком на содержимое
  (в выгрузке клиентов заголовок «Код КЦР» стоит над пустой колонкой, коды — в колонке A без
  заголовка); паттерн кода `^[A-Za-z]{3}\d{5}$` + верхний регистр; ИНН 10/12 цифр, КПП 9 цифр;
  булевы словари («Дейсвующий»/«Не действующий» — с опечаткой источника, «да»/«нет»);
  счётчики — целые, ≥ 0, долг ≤ всего (зеркало CHECK V4); вставленные текстом ошибки Excel
  («#N/A» и др.) трактуются как пустые значения, но строка с ними не считается пустой —
  даёт ошибку строки; дубли кода в файле (клиенты) / в листе-периоде (срезы) — ошибки с номерами
  строк (§11.2); отчётный период среза = имя листа, дата среза — параметр вызова (в файле она
  только в имени); отсутствующая колонка «Всего документов» у срезов даёт null total —
  наследование при применении (§4). В выгрузке клиентов колонки Название/ИНН/КПП/менеджер не
  заполнены, статусные («Статус на …») и долговые колонки не импортируются — срезы берутся из
  отдельного файла. Проверено на обоих реальных файлах: клиенты — 5873 команды, 2 проблемных
  строки (пустой код); срез — 12764 команды, 8 ошибок (по одному «#N/A» на лист + реальные
  дубли ISE83063 в двух листах). Тесты: `ClientExcelParserTest` (9), `SnapshotExcelParserTest`
  (7) — синтетические фикстуры в памяти, воспроизводящие лейауты реальных файлов (реальные
  данные в репозиторий не попадают); `./gradlew build` проходит (49 тестов). Осталось шагом
  3 §11.8: upsert-сервис (без удаления отсутствующих, наследование total, события `IMPORT`)
  и эндпоинты preview/применение.
- п. 8 §10 закрыт (2026-10-28, ветка `feature/auth`): refresh-флоу защищён от гонки — в
  `RefreshTokenService.rotate` чтение токена по хешу переведено на `findByTokenHashForUpdate`
  с `@Lock(LockModeType.PESSIMISTIC_WRITE)` (SELECT … FOR UPDATE) в одной транзакции с пометкой
  ротации и вставкой нового токена; конкурентные `/refresh` с одним токеном сериализуются —
  выдача двух пар токенов из одного невозможна, второй запрос детерминированно видит
  ротированный токен (reuse → отзыв семейства, включая свежий токен победителя; клиент
  перелогинивается). JOIN FETCH из залоченного запроса убран: FOR UPDATE по join блокировал бы
  и строку `users`, создавая окно дедлока с `deleteByUser` при параллельном refresh двух
  разных токенов одного пользователя; пользователь инициализируется в транзакции
  (`Hibernate.unproxy`, OSIV выключен). Тесты: конкурентный сценарий в
  `AuthFlowIntegrationTest` (4 потока × один токен: ровно один 200, остальные 401, токен
  победителя отозван, перелогин работает); `./gradlew build` проходит (50 тестов).
- п. 9 §10 закрыт (2026-10-28, ветка `feature/auth`): очистка просроченных refresh-токенов +
  индекс FK. `@EnableScheduling` на `KcrBackendApplication`; `RefreshTokenCleanupJob`
  (пакет `scheduling/`) по cron `app.cleanup.refresh-token-cron` (общая часть
  `application.yaml`, по умолчанию `0 0 4 * * *`) раз в сутки вызывает
  `RefreshTokenService.deleteExpired()` → новый метод репозитория
  `deleteByExpiryDateBefore(now)` (возвращает число удалённых, job логирует его). Индекс
  `idx_refresh_tokens_user_id` — `@Table(indexes=…)` на `RefreshToken` + миграция
  `V5__refresh_tokens_user_id_index.sql` (dev-БД: применена, индекс проверен). Тесты:
  `RefreshTokenServiceTest` (делегирование), `RefreshTokenCleanupJobTest` (2).
- п. 10 §10 закрыт (2026-10-28, ветка `feature/auth`): `POST /api/v1/auth/logout` — требует
  access-токен (отдельный матчер `authenticated()` в `SecurityConfig` перед permitAll
  `/api/v1/auth/**`), пользователь — из `@AuthenticationPrincipal CustomUserDetails`, отзываются
  все refresh-токены (`AuthService.logout` → `RefreshTokenService.revokeAllForUser` →
  задействован `deleteByUser`), ответ 204; access-токен живёт до истечения срока (stateless,
  блок-листа нет, см. §5). Тесты: `AuthServiceTest`/`RefreshTokenServiceTest` (делегирование),
  e2e-сценарий в `AuthFlowIntegrationTest` (401 без токена, 204 с токеном, оба refresh-токена
  двух сессий мертвы, повторный login работает); `./gradlew build` проходит (56 тестов).
- п. 12 §10 закрыт (2026-10-28, ветка `feature/auth`): антибрутфорс — rate limiting
  `POST /api/v1/auth/login|register` (`AuthRateLimiter` в `security/`, Bucket4j
  `bucket4j_jdk17-core` 8.21.0 + Caffeine-кэш бакетов; token bucket на пару username+IP, IP —
  первый адрес `X-Forwarded-For` либо `remoteAddr`; переполнение → новый
  `TooManyRequestsException` → 429 в едином контракте `ErrorResponse`) и блокировка аккаунта
  после N неудачных попыток (миграция `V6__user_account_lockout.sql`: `users.failed_attempts`
  int NOT NULL DEFAULT 0, `users.locked_until` timestamptz; поля в `User`, порог/длительность —
  `app.security.account-lockout.*`; учёт при загрузке — `CustomUserDetails.isAccountNonLocked()`
  → провайдер отклоняет до сверки пароля, ответ — единый 401; инкремент —
  `AccountLockoutService.registerFailedLogin` в `REQUIRES_NEW`-транзакции, иначе откат
  `@Transactional`-login по 401 стирал бы счётчик; успех сбрасывает счётчик). Параметры —
  `app.security.auth-rate-limit.*` и `app.security.account-lockout.*`, см. §5/§7. Тесты:
  `AuthRateLimiterTest` (2), `AccountLockoutServiceTest` (5), `AuthServiceTest` (делегирование,
  11), e2e в `AuthFlowIntegrationTest`: блокировка до истечения + восстановление (установка
  `locked_until` в прошлое) и 429 при исчерпании бакета (2); `./gradlew build` проходит
  (66 тестов).
- п. 13 §10 закрыт (2026-10-29, ветка `feature/auth`): claims access-токена — subject теперь
  `user.getId()` (username изменяем — токен не должен ломаться при переименовании), добавлены
  `preferred_username` (бывший subject) и `jti` (UUID на каждый токен, задел под блок-лист);
  `email`/`roles` без изменений. `JwtService.extractUsername` → `extractUserId` (subject парсится
  как long), `isTokenValid` принимает `CustomUserDetails` и сверяет id; фильтр грузит
  пользователя по id — `CustomUserDetailsService.loadUserById` (`UserRepository.findById`).
    Access-токены, выданные до изменения (subject = username), после выката невалидны
    (NumberFormatException в фильтре → 401), клиент обновляет пару через `/refresh`;
    refresh-токены не затронуты, при 15-минутном сроке access-токена окно перехода ничтожно.
    Тесты: `JwtServiceTest` (5: round-trip id/claims, уникальность jti, legacy-subject →
    NumberFormatException, истёкший/подделанный токен); e2e-пути фильтра по id покрыты
    существующими сценариями `AuthFlowIntegrationTest` (запросы с access-токеном, logout);
    `./gradlew build` проходит (68 тестов).
- п. 14 §10 закрыт (2026-10-30, ветка `feature/auth`): иерархия исключений сведена к единственному
  доменному `DomainException` + enum `ErrorCode` (имя константы — машиночитаемый код; поля:
  HTTP-статус и сообщение по умолчанию, §6); пакет `exception/base/` и шесть предметных
  исключений (`CredentialsAlreadyExists` и др.) удалены, все точки бросания переведены на коды.
  `ErrorResponse` — `code` + `message` + `timestamp` (ISO-8601 `Instant` вместо epoch-millis);
  `RestAuthenticationEntryPoint` в том же контракте. `DataIntegrityViolationException` в хендлере
  различается по имени констрейнта (Hibernate `getConstraintName`, фолбэк — парсинг сообщения
  драйвера): `uk_users_username`/`uk_users_email`/`uk_clients_kcr_code` → точный код 409, прочее —
  `DATA_CONFLICT` (раньше любое нарушение отвечало «Username or email already taken»). Тесты:
  новый `GlobalExceptionHandlerTest` (6: маппинг кода/статуса/тела, известные и неизвестные
  констрейнты, отсутствие имени, фолбэк по сообщению драйвера), обновлены `AuthServiceTest`,
  `ClientServiceTest`, `AuthRateLimiterTest` (ассерты на `ErrorCode`), e2e-контракт 401 в
  `ClientApiIntegrationTest` (`"code":"AUTHENTICATION_REQUIRED"`, `timestamp` — ISO-строка);
  `./gradlew build` проходит (74 теста).
- п. 23 §10 закрыт (2026-10-30, ветка `feature/auth`): проверка access-токенов переведена
  на resource server Spring Security (`spring-boot-starter-oauth2-resource-server`), рукописный
  `JwtAuthenticationFilter` удалён. `NimbusJwtDecoder` (бин `SecurityConfig`, тот же ключ)
  валидирует подпись и срок — один парсинг на запрос; алгоритм HS256 закреплён явно и в выдаче
  (`JwtService.signWith(key, Jwts.SIG.HS256)`), и в декодере (раньше jjwt выбирал HSxxx по
  длине ключа молча — теперь совпадение сторон гарантировано кодом). `JwtAuthenticationConverter`
  (`security/`): subject → id → пользователь из `loadUserById` через Caffeine-кэш (TTL 30 с,
  максимум 10 000) → `UserAuthenticationToken` с principal `CustomUserDetails` — контроллеры
  и сервисы не изменились; БД выведена из горячего пути. Этим же закрыты п. 20 (загрузка
  пользователя на каждый запрос; debug-лог причины 401 — в `RestAuthenticationEntryPoint`) и
  подпункты 32(а)/(в) (токен удалённого пользователя → `InvalidBearerTokenException` → 401
  вместо 500; парсинг токена один раз вместо 2–3) — из п. 32 остался только catch-all для
  непредвиденных исключений. Entry point подключён и в `oauth2ResourceServer`: без этого
  ошибки bearer-фильтра уходили в дефолтный sendError с пустым телом мимо единого контракта.
  Мёртвые после замены методы `JwtService` (`extractUserId`/`isTokenValid`) удалены. Тесты:
  `JwtAuthenticationConverterTest` (4: конвертация с ролями, legacy-subject, неизвестный
  пользователь, кэш), `JwtServiceTest` переписан под выдачу-only (2), e2e «токен удалённого
  пользователя → 401 с телом контракта» в `AuthFlowIntegrationTest`; `./gradlew build`
  проходит (76 тестов).
- п. 15 §10 закрыт (2026-10-31, ветка `feature/auth`): `BCryptPasswordEncoder` заменён на
  `DelegatingPasswordEncoder` (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`):
  новые хеши записываются с префиксом алгоритма (`{bcrypt}$2a$…`), дефолт остаётся bcrypt —
  само хеширование не менялось, это задел: будущая смена алгоритма (Argon2id и т.п.) — смена
  дефолта кодировщика, без перекладки записанных данных. Существующие хеши без префикса
  матчатся через `setDefaultPasswordEncoderForMatches` (fallback-BCrypt) — БД не тронута,
  миграции не потребовались. Тесты: `SecurityConfigPasswordEncoderTest` (3: `{bcrypt}`-префикс
  encode, матчинг хеша в новом и legacy-формате); `./gradlew build` проходит (79 тестов).
- п. 16 §10 закрыт (2026-11-01, ветка `feature/auth`): нормализация email до валидации —
  компакт-конструкторы record'ов `RegisterRequest` / `ClientCreateRequest` / `ClientUpdateRequest`
  (email/contactEmail: `trim` + blank → null), правило — одно на все три DTO, в
  `common/EmailNormalizer` (пакет `common/` — общий код без зависимостей от слоёв, предвестник
  целевой структуры §11.5). Jackson собирает record через canonical
  constructor — нормализация выполняется при десериализации, до `@Valid`: краевые пробелы больше
  не дают 400 от `@Email`, `@Size(max=255)` проверяет тримнутую длину. Один источник правды:
  `AuthService.normalizeEmail` удалён — сервисы получают уже нормализованное значение (уникальность
  email и запись в БД идут по нему же). Семантика: blank email в register → NULL (как и раньше
  сервисно), в PATCH клиентов blank contactEmail = «не менять» (как null; очистить email пустой
  строкой через PATCH теперь нельзя — поле не отличает blank от отсутствия, опциональность
  очистки — вопрос будущего API). Тесты: `EmailNormalizationTest` (3, конструкторы всех трёх DTO),
  e2e в `AuthFlowIntegrationTest` (register с пробелами → 201 и тримнутый email в БД, дубль по
  нормализованному значению → 409, blank → NULL) и `ClientApiIntegrationTest` (POST с пробелами
  вокруг contactEmail → 201 и тримнутое значение, PATCH blank → «не менять»); `./gradlew build`
  проходит (84 теста).
- п. 27 §10 закрыт (2026-11-02, ветка `feature/auth`): bootstrap первого администратора —
  `AdminBootstrap` (`bootstrap/`, `ApplicationRunner`). Условный режим на каждом старте: если
  пользователей с ролью ADMIN нет (`existsByRole(ADMIN)` — новый метод `UserRepository`),
  создаётся учётная запись из `ADMIN_USERNAME`/`ADMIN_PASSWORD` (пароль — тем же
  `PasswordEncoder`, `{bcrypt}`-префикс; роль ADMIN, email NULL); есть хоть один ADMIN — no-op
  (идемпотентно). Учётные данные пусты: prod — `IllegalStateException` при старте (fail-fast:
  чистый деплой без возможности управления не должен подниматься), dev — WARN и продолжение;
  занятый username — fail-fast в обоих профилях (ошибка конфигурации, текст подсказывает
  выбрать другой `ADMIN_USERNAME`). Для эксплуатации — режим `--create-admin` (тот же раннер,
  опция `ApplicationArguments`): создаёт учётную запись из тех же переменных независимо от
  существующих ADMIN (добавление администраторов) и завершает приложение
  (`SpringApplication.exit`; при работающем инстансе запускать с `--server.port=0`, см. §8).
  Свойства `app.admin.username`/`app.admin.password` (общая часть `application.yaml`,
  `${ADMIN_USERNAME:}`/`${ADMIN_PASSWORD:}` — в dev через `.env`; семантика применения
  в раннере, поэтому prod падает только когда bootstrap действительно нужен), см. §5/§7.
  Проверено на dev-БД: создание с `{bcrypt}`-хешем, завершение после команды, повторный запуск
  → fail-fast «already exists». Тесты: `AdminBootstrapTest` (7: no-op при существующем ADMIN,
  создание с encoded-паролем, dev-skip и prod fail-fast без учётных данных, занятый username,
  forced-режим с существующими ADMIN и без учётных данных); `./gradlew build` проходит
  (91 тест).

- пп. 28–32 §10 закрыты (2026-11-03, ветка `feature/auth`) — вся группа P1 «до первого
  продакшена»:
  - п. 28: пагинация валидируется constraint-аннотациями на параметрах `ClientController`
    (`@Min(0) page`, `@Min(1) @Max(PageLimits.MAX_PAGE_SIZE) size`; встроенная method-validation
    Boot — без `@Validated`), нарушение → 400 `VALIDATION_FAILED` (`HandlerMethodValidationException`
    в `GlobalExceptionHandler` со сводкой по параметрам). Константа `PageLimits.MAX_PAGE_SIZE`
    (100) — в `common/`, под эндпоинты истории §11.4.
  - п. 29: доменные правила формата — в переиспользуемых constraint-аннотациях
    `common/validation/` (`@Inn` — 10 или 12 цифр, `@Kpp` — 9, `@KcrCode` — канонический
    `^[A-Z]{3}\d{5}$`); паттерны — публичные константы валидаторов, парсер импорта
    (`ClientExcelParser`, `ExcelCells.matchesKcrCode` — регистронезависимо, сырые ячейки)
    использует те же правила: источник данных заменяем, правило одно. Нормализация кода KCR
    при записи — `common/KcrCodeNormalizer` (trim + верхний регистр) в компакт-конструкторе
    `ClientCreateRequest`, `@KcrCode` проверяет канонический вид. Ручной POST/PATCH теперь
    принимает 10-значный ИНН (раньше только 12 — расхождение с парсером устранено).
  - п. 30: регистронезависимая уникальность username/email — функциональные unique-индексы
    `idx_users_username_lower`/`idx_users_email_lower` по `lower(...)` (миграция
    `V7__users_case_insensitive_identity.sql`; выбор против `citext` — без
    `CREATE EXTENSION`-привилегий). Запись и поиск — в нижнем регистре
    (`common/UserIdentityNormalizer`): компакт-конструкторы `RegisterRequest`/`LoginRequest`
    (username и email) и `AdminBootstrap` (config-значение минует DTO); email-нормализация
    `contactEmail` клиентов не менялась (только trim). `Admin`/`admin` — один аккаунт,
    вход не чувствителен к регистру. Маппинг 409 дополнен именами новых индексов
    (`uk_*` оставлены для совместимости истории миграций).
  - п. 31: инкремент счётчика блокировки — атомарный bulk-UPDATE
    `UserRepository.registerFailedLogin` (`failedAttempts + 1`, `lockedUntil` — `case` при
    достижении порога, параметры конфигурации передаются вызывающим; read-modify-write над
    сущностью терял инкременты при параллельных неудачных попытках), сброс — условный
    bulk-UPDATE `resetFailedLogins` (чистый аккаунт не затрагивается). `REQUIRES_NEW`
    у `registerFailedLogin` сохранён.
  - п. 32 (остаток — catch-all): `@ExceptionHandler(Exception.class)` → 500
    `INTERNAL_ERROR` в едином контракте, стек — в лог, ответ нейтрален.
    `AccessDeniedException` перебрасывается отдельным хендлером в security-фильтр —
    403 `@PreAuthorize` остался в `ExceptionTranslationFilter`, не превращаясь в 500.
  Тесты: `GlobalExceptionHandlerTest` (10: новые имена индексов, catch-all, rethrow
  AccessDenied), `AccountLockoutServiceTest` переписан под bulk-методы (3),
  `ClientRequestValidationTest` (7: констрейнты и нормализация DTO), e2e в
  `AuthFlowIntegrationTest` (регистронезависимость username/email) и `ClientApiIntegrationTest`
  (`page=-1`/`size=101` → 400, `size=100` → 200, ИНН 10 цифр POST/PATCH, код KCR
  нормализуется/отвергается); `./gradlew build` проходит (101 тест).

- фильтр импорта срезов по отчётному периоду (2026-11-08, ветка `feature/operator-cycle`):
  `reportingPeriod` — обязательный параметр preview/apply срезов; импортируются только листы,
  чьё имя совпадает с выбранным периодом (регистр/пробелы/ё не значимы), многопериодная
  выгрузка грузится по одному периоду за раз; листы других периодов с данными попадают в
  `skippedSheets` отчётов; ни одного совпадения → 400 `IMPORT_PERIOD_NOT_FOUND`; фронтенд —
  поле периода на странице импорта, сброс результатов при смене параметров; тесты
  (`SnapshotExcelParserTest` 12, интеграционные) зелёные (108 тестов).

- переход на package-by-feature для домена «клиенты» (2026-11-08, закрыт вопрос 6 §11.7):
  `clients/` — ClientController, WorklistController, ClientService, StagePolicy, model/
  (Client, ClientType, WorkStage), repository/, dto/, events/ (история + претензии);
  `PageResponse` переехал в `common/`; snapshot-домен остался слоёным до шага «Динамика»
  (перенос `auth` — отдельный рефакторинг). Dev-профиль переведён на БД compose-стенда
  (теперь `kcr-db` общего стенда корня репозитория, localhost:5433/kcr).

- **операторский цикл** — шаги 1 и 5 §11.8 + события IMPORT из хвоста шага 3 (2026-11-08,
  ветка `feature/operator-cycle`, «Блок А» MVP-критериев 7–13):
  - `V8__client_work_fields.sql`: `clients.stage` (WorkStage, default NOT_IN_WORK),
    `assignee_id` FK users, `next_contact_at` DATE, `created_at` timestamptz not null
    (backfill моментом миграции); индексы по assignee и частичный по next_contact_at;
    аудит — `JpaAuditingConfig` + `@CreatedDate` + `@EntityListeners` (закрыта оставшаяся
    часть п. 24 §10);
  - `V9__client_event.sql`: `client_events` — единая история с автором и временем
    (§4); ручные CALL/LETTER_SENT/COMMENT (`POST /api/v1/clients/{id}/events`: CALL требует
    `callResult`, call-only поля отвергаются для других типов, системные типы — только
    сервисом), звонок обновляет `clients.next_contact_at` и может сменить этап (отдельное
    событие STAGE_CHANGED в той же транзакции); `GET` — от новых к старым, пагинация;
  - `V10__claim_sanction.sql`: претензии и санкции (§4), мутации — админ, дублирование
    CLAIM/SANCTION-событиями; чтение — `GET /api/v1/clients/{id}/claims` (обе роли);
  - атомарное взятие — условный bulk-UPDATE `assignAssigneeIfFree` (`WHERE assignee_id IS
    NULL`, §11.2), гонка покрыта тестом; `PATCH /assignment` (админ; `assigneeId: null` —
    освободить, одноцелевая семантика — не null-skip), `PATCH /stage` по правилам StagePolicy
    (решение по вопросу 1 §11.7), `GET /api/v1/worklist/today` (оператору — свои, админу — все),
    фильтры `stage`/`assigneeId`/`unassigned` и сортировка по stage/nextContactAt в списке
    клиентов; `GET /api/v1/users` (админ) — справочник для переназначения;
  - события IMPORT: обе apply-операции импорта пишут по событию на затронутого клиента
    («Импорт клиентов: создан/обновлён», «Импорт срезов (период, срез): не передано N»),
    автор — загружающий админ; пересчёт категорий остаётся шагу «Динамика»;
  - фронтенд `client/kcr-frontend`: страница «Сегодня» (/today), блок работы в карточке
    (взять/этап/переназначение), лента истории + форма события (звонок с результатом,
    договорённостью, сроками, ссылкой на запись и сменой этапа), претензии/санкции, фильтры
    этапа и ответственного в списке, русские подписи справочников (`src/labels.js`);
  - справочники CallResult/ClaimStatus/SanctionStatus приняты (закрыт вопрос 3 §11.7);
    новые коды ошибок: `CLIENT_ALREADY_TAKEN`, `USER_NOT_FOUND`, `STAGE_CHANGE_FORBIDDEN`,
    `EVENT_TYPE_NOT_ALLOWED`, `EVENT_FIELD_NOT_ALLOWED`, `CALL_RESULT_REQUIRED`,
    `CLAIM_NOT_FOUND`, `SANCTION_NOT_FOUND`;
  - тесты: `ClientEventServiceTest` (9), `ClaimServiceTest` (8), `ClientServiceTest` +
    10 (take/assignment/stage), `OperatorCycleIntegrationTest` (2, включая гонку взятия),
    `ClaimApiIntegrationTest`, IMPORT-события в `ImportApiIntegrationTest`, справочник
    пользователей; `./gradlew build` проходит (139 тестов).

- **динамика срезов и единый список на обзвон** — шаги 2 и 4 §11.8 (ветка `feature/call-list`,
  слит в develop 2026-11-19; миграция `V11__snapshot_dynamics_and_call_list.sql`):
  - `V11`: колонки `document_transfer_snapshots.dynamics_category` (enum DynamicsCategory)
    и `previous_not_transferred`, таблица `call_list_items` (единый закреплённый список на обзвон);
  - `snapshots/DynamicsService`: детерминированный расчёт 7 категорий динамики (§11.2) и
    сводной долговой информации `ClientDebtInfo`; расчёт и сохранение динамики при импорте срезов
    (`SnapshotImportService`);
  - `clients/WorklistService` и `WorklistController`:
    - `POST /api/v1/worklist/preview` (только ADMIN): предпросмотр списка клиентов с фильтрацией
      по долговой информации (наличие долга, minDebt), проценту непереданных (порог 5% по ТЗ)
      и низкой динамике (`UNCHANGED_OR_GROWING`, `PERSISTENT`);
    - `POST /api/v1/worklist/generate` (только ADMIN): фиксация отобранных клиентов в единый
      список на обзвон для всех операторов; клиенты на этапе `NOT_IN_WORK` автоматически
      переводятся в этап `IN_POOL` с системной записью в истории клиента;
    - `GET /api/v1/worklist/call-list` (ADMIN и OPERATOR): получение единого закреплённого списка;
    - `DELETE /api/v1/worklist/call-list` (только ADMIN): очистка списка;
  - фронтенд `client/kcr-frontend`: страница «Список на обзвон» (`/call-list`) с вкладками
    закреплённого списка и панели фильтрации/предпросмотра для администратора, бейджи динамики
    (`DynamicsBadge`), кнопка «Взять в работу» для операторов прямо из закреплённого пула;
  - тесты: `DynamicsServiceTest` (9), `WorklistServiceTest` (4), `WorklistControllerTest` (4);
    на момент сдачи прогонялись юнит-тесты, полная сборка с интеграционными — нет.

- единый compose-стенд и контейнеризация фронтенда (2026-11-08, 75488e2): корневой
  `docker-compose.yml` (name `kcr`: db/app/ml/frontend, volume `kcr-backend_pgdata` сохранён),
  фронтенд собран в образ nginx — статика SPA + прокси `/api/v1/ai/**` → ml, `/api/v1/**` → app
  (§8). Композ из `server/kcr-backend` и `ml/` удалён, секреты — в корневом `.env`.

- ИИ-помощник (2026-11-08, ветка `feature/ai-agent`, PR #9): сервис `ml/` (Python/FastAPI:
  аутентификация по токенам бэкенда, read-only `DataSourcePort` поверх REST, use cases UC-1…UC-5,
  70 тестов) и AI-страницы фронтенда (чат `/ai`, аналитика, приоритизация, сводка в карточке,
  экспорт xlsx). Этим закрыт вопрос 5 §11.7 (LLM живёт в `ml/`), шаг 6 §11.8 реализован вне этого
  бэкенда; бэкенд трафик ИИ не проксирует. Ограничения — п. 41, 42, 47, 55 §10.

- package-by-feature для всех доменов (2026-11-08, ветка `refactor/package-by-feature`, PR #10):
  `auth/`, `users/` (выделен из auth: справочник + `AdminBootstrap`), `snapshots/`,
  `common/error/`; тесты перенесены зеркально (итог — дерево §3, зависимости доменов — там же).

- лимит файла импорта (2026-11-19, e57b8a3): nginx-прокси `client_max_body_size 20M`, бэкенд
  `max-file-size=10MB` / `max-request-size=15MB`, `MaxUploadSizeExceededException` → 400
  `VALIDATION_FAILED` в едином контракте (проверено живьём 2026-11-28: файлы 11 и 16 МБ дают
  корректный 400 с JSON), на фронте ограничение 10 МБ до отправки.

- батчевый импорт (2026-11-21, 695b26c; на 2026-11-28 в `origin` не запушен): `saveAll` вместо
  построчных save, `hibernate.jdbc.batch_size=50` + `order_inserts`/`order_updates`, миграция
  `V12` — sequence-ы с шагом 50 вместо IDENTITY для трёх таблиц (IDENTITY заставлял Hibernate
  делать flush после каждой вставки); срезы для предпросмотра и закреплённого списка читаются
  одним запросом по набору клиентов. Проверялись только юнит-тесты: **интеграционные тесты после
  этого падают (8 из 157)** — V12 оставила старый IDENTITY включённым, п. 38 §10.

- полное ревью и синхронизация документации (2026-11-28): найденные проблемы внесены в §10
  как пункты 38–63 (по всем трём сервисам монорепо), разделы §1–§9 и §11 приведены в соответствие
  с кодом.

Выявленные проблемы и требуемые изменения собраны в §10 (архитектурные ревью 2026-10-24,
2026-10-29 и 2026-11-28); ранее перечислявшиеся здесь «известные особенности» включены туда.

---

## 10. Требуемые изменения (архитектурные ревью)

Раздел фиксирует проблемы, выявленные ревью от 2026-10-24 (пп. 14–26), повторным ревью
2026-10-29 по фактическому коду после закрытия пп. 1–13 и 17 (пп. 27–37) и полным ревью
монорепо 2026-11-28 (пп. 38–63 — по всем трём сервисам), и целевые решения.
Пункты пронумерованы сквозной нумерацией, порядок внутри группы — порядок внедрения. Закрывая пункт,
обнови затронутые разделы (§1–§9) в том же изменении и удали закрытый пункт отсюда;
нумерация оставшихся пунктов не сдвигается — номера используются как ссылки из других разделов.
Факты в пунктах 38–63 подтверждены запуском (тесты, живые запросы к API), а не только чтением кода.

### P1 — блокирует (ревью 2026-11-28)

38. **После V12 у таблиц два генератора id — интеграционные тесты красные.**
    `V12__batch_sequences.sql` завела для `clients`, `client_events` и `document_transfer_snapshots`
    sequence-ы с шагом 50, но колонки `id` остались `GENERATED BY DEFAULT AS IDENTITY` со своими
    счётчиками. Hibernate выдаёт id из пула `[N−49 … N]` новой sequence, а любая вставка мимо
    Hibernate (интеграционные тесты сеют строки через `JdbcTemplate`, ручные SQL-правки) берёт
    следующий id из старого identity — значения пересекаются, конфликт `clients_pkey`. Результат:
    `./gradlew test` — 8 из 157 падают (`DuplicateKeyException` на посеве, 409 `DATA_CONFLICT`
    в `ImportApiIntegrationTest`). Целевое решение: новая миграция — `ALTER TABLE … ALTER COLUMN id
    DROP IDENTITY` + `SET DEFAULT nextval('<таблица>_seq')` для всех трёх таблиц (сырой INSERT без id
    продолжает работать и не пересекается с пулом инстанса); пересинхронизировать счётчики на
    существующих базах; в тестах — посев через явные id или через сами сервисы.

39. **Порог 5% не работает на реальных данных: нечем заполнить `total_documents`.**
    В реальной выгрузке срезов нет колонки «Всего документов», а долговые колонки выгрузки клиентов
    не импортируются; итог наследуется от предыдущего среза периода, который тоже пуст →
    `percentNotTransferred = null` у всех. Фильтр списка на обзвон `minUntransferredPercent`
    (в интерфейсе по умолчанию 5%) отклоняет всех, кроме новичков; в карточке доля — «—».
    Центральное правило ТЗ неприменимо. (В синтетической dev-БД итоги заполнены — у всех 5945
    срезов, — поэтому там проблема не видна.) Нужно решение: (а) единый шаблон срезов с колонкой «Всего»
    (вопрос 4 §11.7); (б) импорт итогов за период из выгрузки клиентов; (в) ввод итога вручную/API.
    Пока не решено — интерфейс должен явно предупреждать «доля неизвестна у N клиентов», а не
    молча отдавать пустой список.

40. **«Новичок» считается от `clients.created_at`.**
    (а) `created_at` — момент появления строки в этой БД: после первого импорта все клиенты
    считаются новичками 6 месяцев → каждый должник получает `NEWCOMER_RISK`, остальные категории
    маскируются, а порог из п. 39 обходится для всех — список на обзвон заполняется всеми должниками.
    (б) `DynamicsService.monthsBetween` обрезает отрицательное значение нулём → срез с датой раньше
    `created_at` (импорт истории) всегда `NEWCOMER_RISK`. В dev-БД это видно напрямую: из 5945
    сохранённых срезов есть только `NO_DEBT` (3737) и `NEWCOMER_RISK` (2208), ни одного среза с
    другой категорией долга — даты срезов (январь–май 2026) раньше момента исходной загрузки
    клиентов. Целевое решение: брать дату подключения
    из данных (дата первого КЦР/договора из выгрузки — вопрос 2 §11.7) либо заполнять `created_at`
    исторической датой при загрузке; возвращать `false`, если дата среза раньше даты появления;
    то же в `ml/src/kcr_ml/domain/dynamics.py`.

41. **kcr-ml обрабатывает только первые 500 клиентов и ходит в бэкенд построчно.**
    `AI_MAX_SCAN_CLIENTS=500` (умолчание) молча обрезает справочник (`iter_all_clients`),
    `load_clients_with_snapshots` и `prioritize` делают по GET `/clients/{id}/snapshots` на клиента,
    последовательно. При ~5,9 тыс. клиентов аналитика (`summary`, `dynamics`, `top-debt`) и
    приоритизация считаются по ≤ 500 клиентам (первые по id), признака усечения в ответе нет —
    показанные как факт числа неверны; страница аналитики запускает три таких прохода
    (≈1500 последовательных запросов). Целевое решение: bulk/агрегатные эндпоинты бэкенда
    (`GET /api/v1/snapshots?…` по набору клиентов и периоду, `GET /api/v1/stats/summary` — остаток
    шага 4 §11.8), поля `truncated`/`scanned` в ответах, ограниченный параллелизм, общий httpx-клиент.

42. **Категории динамики: смешение периодов, импорт «задним числом», дубль правил.**
    (а) `classifySnapshot` упорядочивает все срезы клиента по дате, не разделяя отчётные периоды:
    «предыдущий» может принадлежать другому периоду, а «погашенный цикл» распознаётся по нулевому
    срезу другого периода (новый квартал без документов) → ложные `REAPPEARED`/`DECREASING`;
    формулировка §11.2 — «относительно предыдущего среза того же периода». (б) `SnapshotImportService.apply`
    дописывает новый срез в конец истории без сортировки: периоды импортируются по одному, и при
    загрузке более раннего периода позже `previous_not_transferred` и категория считаются относительно
    *более позднего* среза, уже сохранённые срезы не пересчитываются — карточка (сохранённое) и списки
    (пересчёт на лету) расходятся. В dev-БД на 2026-11-28 расхождение почти тотальное: сохранённая
    категория последнего среза у всех 1330 должников — `NEWCOMER_RISK`, а `POST /worklist/preview`
    пересчитывает их как `FIRST_OCCURRENCE` 540, `PERSISTENT` 492, `DECREASING` 287,
    `NEWCOMER_RISK` 11 (после загрузки срезов значения `clients.created_at` изменились, а
    сохранённые категории за ними не последовали). (в) Правила реализованы дважды — Java `DynamicsService` и Python
    `domain/dynamics.py` — и уже расходятся в деталях (регистр при сравнении периода, исключение
    для новичков у порога). Целевое решение: сравнение в пределах периода, как записано в §11.2
    (либо осознанно зафиксировать межпериодную семантику в §11.2 и тестах); сортировка истории при
    импорте; единый источник — kcr-ml берёт категорию из `SnapshotResponse` (колонка V11) и убирает
    свой классификатор; общие тест-векторы.

43. **Контракт ошибок: 403, 405, 415, отсутствующая часть `file`.**
    Проверено живьём (таблица в §6): отказ `@PreAuthorize` — 403 с пустым телом; неподдерживаемый
    метод, неподдерживаемый `Content-Type` и multipart без части `file` — 500 `INTERNAL_ERROR`
    с записью ERROR в лог; на фронте пользователь видит «HTTP 403». Целевое решение:
    `accessDeniedHandler` в `exceptionHandling` с телом `ErrorResponse` (новый код
    `ACCESS_DENIED`); хендлеры `HttpRequestMethodNotSupportedException` (405),
    `HttpMediaTypeNotSupportedException` (415), `MissingServletRequestPartException` (400) — либо
    один хендлер семейства `ErrorResponseException`; заголовок `WWW-Authenticate: Bearer` у 401.

44. **Права оператора в операторском цикле.**
    Проверено живьём: (а) `POST /clients/{id}/events` не проверяет владельца — оператор фиксирует
    звонки/комментарии и меняет `next_contact_at` на чужом клиенте (смена этапа ограничена
    `StagePolicy`, события — нет); (б) `POST /clients/{id}/take` не смотрит на этап — берётся любой
    свободный клиент, включая `NOT_IN_WORK`, то есть отбор администратора обходится; (в) взятие не
    меняет этап — клиент с ответственным остаётся `IN_POOL`/`NOT_IN_WORK`, фильтры по этапу вводят
    в заблуждение, kcr-ml считает его «не в работе» (п. 55). Нужно решение и правки: события — только
    своему клиенту (админ — любому); `take` — только `IN_POOL` без ответственного (либо явный
    признак «можно брать вне пула»); при взятии `IN_POOL → FIRST_CALL` автоматом; определить, что
    происходит с ответственным при возврате администратором в `NOT_IN_WORK`/`COMPLETED`
    (сейчас остаётся, взять клиента другому нельзя).

### P2 — надёжность, безопасность, производительность (ревью 2026-11-28)

45. **Открытая самостоятельная регистрация.** `POST /api/v1/auth/register` публичен и есть в
    интерфейсе — любой, кому доступен API, создаёт себе `OPERATOR` с доступом к справочнику (ИНН,
    контакты, история звонков). Для внутренней системы нужно решение: закрыть (пользователей заводит
    админ), приглашения/доменные ограничения почты либо флаг `app.security.registration-enabled`
    (в prod выключен). Связано с п. 58.

46. **Антибрутфорс за прокси и блокировка как DoS.** (а) nginx стенда не ставит `X-Forwarded-For`/
    `X-Real-IP` — бэкенд для всех видит адрес прокси; (б) `AuthController.clientIp` безусловно верит
    первому значению XFF, а nginx передаёт заголовки клиента как есть — подмена
    `X-Forwarded-For` даёт злоумышленнику новый бакет на каждый запрос, лимит за стендом
    обходится; (в) блокировка
    считается только по username: пять неверных паролей блокируют любой известный аккаунт
    (включая `admin`) на 15 минут, повторно без ограничений. Целевое решение: в nginx
    `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` и `X-Real-IP $remote_addr`;
    доверие XFF — явным флагом, по умолчанию выключено (дополняет п. 35); блокировка по паре
    username+IP либо с нарастающей задержкой; security-события в лог (п. 19).

47. **Токен в query WebSocket и отсутствие лимитов на LLM.** `WS /api/v1/ai/chat?token=<access>`
    — access-токен попадает в access-лог nginx и uvicorn (`docker logs`): 15-минутный bearer-токен
    читается из логов; соединение принимается до проверки токена. LLM-эндпоинты (`prioritize`,
    `brief`, чат) без rate limiting и квот — любой аутентифицированный пользователь генерирует
    затраты у провайдера. Целевое решение: одноразовый ticket (REST выдаёт короткоживущий ключ, WS
    принимает только его — прод-опция из ARCHITECTURE.md kcr-ml); до того — `access_log off` для
    WS-location либо маскирование, uvicorn без access-лога, проверка токена до `accept()`;
    лимиты на пользователя; проверка длины `AUTH_JWT_SECRET` при старте kcr-ml.

48. **`recordUrl` без валидации схемы — stored XSS.** В `EventCreateRequest.recordUrl` только
    `@Size`; значение `javascript:alert(1)` сохраняется (проверено) и рендерится в `HistoryFeed`
    как `<a href=…>` — клик по «прослушать» исполняет скрипт в origin приложения (React 18 лишь
    пишет предупреждение). Целевое решение: разрешать только `http(s)://` в DTO (`@Pattern` или
    своя constraint) и в `safeUrl`-хелпере на фронте.

49. **N+1 и тяжёлые выборки.** (а) `GET /clients` — `ClientResponse.from` читает
    `assignee.username` по строке (до 100 лишних SELECT на страницу); (б) история клиента —
    `author.username` по событию; (в) предпросмотр/генерация списка на обзвон — ответственный по
    каждому клиенту; (г) `SnapshotImportService.preview` — SELECT на строку (`resolveTotal`), тогда
    как `apply` уже батчевый; (д) предпросмотр/генерация грузят всех клиентов и все их срезы в память
    и передают тысячи id в `IN (…)`; (е) `GET /worklist/call-list` без пагинации. Целевое решение:
    `@EntityGraph`/`JOIN FETCH` либо проекционные запросы для списков; батчевый `resolveTotal` в
    предпросмотре; фильтрация по долгу на стороне БД, когда колонки V11 станут достоверными (п. 42);
    пагинация закреплённого списка.

50. **Шум IMPORT-событий в истории клиента.** `ClientUpsertService.apply` пишет «Импорт клиентов:
    обновлён» каждой строке файла при каждом импорте, даже если ни одно поле не изменилось (тысячи
    событий на загрузку — по одному на клиента); `SnapshotImportService.apply` — по событию на
    каждый срез (при еженедельных загрузках лента зарастает, реальные звонки уходят на дальние
    страницы; в комментарии только «не передано N»). Целевое решение: событие только при фактическом
    изменении полей и с их перечнем в комментарии; для срезов — одно событие на клиента на импорт
    с дельтой («не передано 12 → 9»); в ленте — группировка/фильтр по типу.

51. **Импорт: идемпотентность и канонический период.** (а) Нет защиты от повторного применения
    того же файла за тот же период и дату — каждый apply создаёт новые строки срезов и события
    (двойная отправка дублирует тысячи строк и искажает динамику); (б) `reporting_period`
    хранится как введённый администратором текст, а лист подбирается по нормализованному имени —
    один лист, загруженный как «1 кв 2026» и «1 квартал 2026», даёт два разных периода, наследование
    итога и фильтры ломаются; (в) исключения парсера гасятся в `IMPORT_FILE_UNREADABLE` без лога
    причины; (г) скачиваемый файл проблемных строк из ТЗ не сделан (только JSON). Целевое решение:
    отклонять либо заменять по `(клиент, период, дата)` (решение); хранить имя листа как период;
    логировать причину; выгрузка проблемных строк в xlsx.

52. **Список на обзвон: фильтры, расхождение предпросмотра и фиксации, аудит.**
    (а) у `WorklistFilterRequest` нет критериев по этапу/ответственному/«не в работе» — администратор
    не может исключить клиентов, уже взятых в работу (сценарий ТЗ «не в работе»), они попадают в
    общий список; (б) `CallListPage.handleGenerate` отправляет *текущее* состояние фильтров, а кнопка
    показывает число из предпросмотра — закреплённый список может отличаться от просмотренного
    (поле `clientIds` в запросе есть именно для фиксации); (в) `generate` с фильтрами без совпадений
    всё равно очищает существующий список (`deleteAllFast` до проверки пустоты), подтверждения при
    замене нет; (г) без долговых фильтров в список попадают клиенты без долга и переводятся
    в `IN_POOL`; (д) составление/очистка списка не оставляют следа (кто, когда, сколько);
    (е) системный комментарий «Included in call list (common pool)» — единственный на английском.
    Целевое решение: фильтры этапа/ответственного; фиксация по `clientIds` из предпросмотра;
    подтверждение и сохранение списка при пустой выборке; требование долга > 0 на сервере;
    событие/лог на составление и очистку; русские комментарии.

53. **Ручные правки карточки не попадают в историю; системные комментарии.**
    (а) `PATCH /clients/{id}` и `POST /clients` не пишут событие — правки контактов, менеджера,
    типа не прослеживаются, хотя ТЗ требует единую историю каждого действия; (б) обновление при
    импорте молча перезаписывает вручную исправленные поля; (в) системные комментарии содержат имена
    enum («Этап: NOT_IN_WORK → IN_POOL», «статус NEW → SENT») вместо русских подписей;
    (г) очистить необязательное поле через PATCH нельзя (null = «не менять»), вернуть «нет данных»
    для триады тоже. Целевое решение: событие на ручную правку (с перечнем изменённых полей) и на
    перезапись вручную изменённых значений при импорте (либо защита таких полей); структурированные
    поля события (`fromStage`/`toStage`) с подписью на клиенте; явная семантика очистки (JSON Merge
    Patch либо признак `clear`).

54. **Часовой пояс «сегодня».** `ClientService.findDueToday` берёт `LocalDate.now()` в зоне
    сервера, kcr-ml — `date.today()`, контейнеры работают в UTC, фронт показывает дату браузера:
    с 00:00 до 03:00 МСК списки «сегодня» расходятся на сутки. Целевое решение: настраиваемая
    бизнес-зона (`app.business-zone`, по умолчанию `Europe/Moscow`) во всех расчётах «сегодня»
    обоих сервисов; `TZ` для контейнеров либо явная зона у `@Scheduled` (п. 37).

55. **kcr-ml: критерии приоритизации и устойчивость чата.** (а) `ClientView.in_work` =
    этап не `NOT_IN_WORK`/`IN_POOL`; поскольку взятие не меняет этап (п. 44), клиент, уже занятый
    оператором, считается «не в работе» — выборка «не в работе» возвращает занятых; (б) `reasons`
    содержат имена enum («динамика: UNCHANGED_OR_GROWING», «этап: FIRST_CALL») и показываются
    пользователю; (в) в `matches()` нет исключения `NEWCOMER_RISK` для `min_untransferred_percent`
    (в фильтре бэкенда оно есть), константа `POOL_MIN_PERCENT` объявлена и не используется;
    (г) обработчики инструментов бросают `ValueError` на неожиданных значениях enum от модели —
    ловится только `ValidationError` — итерация обрывается общей ошибкой, сокет закрывается.
    Целевое решение: `in_work` = «есть ответственный или рабочий этап»; русские подписи; согласовать
    правило порога с бэкендом (единый источник — п. 42); ловить `ValueError` в `_execute`.

56. **Фронтенд: устойчивость сессии и рендера.** (а) в `api/ai.js` нет refresh по 401 (в отличие
    от `api/client.js`) — после 15 минут простоя AI-страницы показывают ошибку входа, пока какой-либо
    запрос к бэкенду не обновит пару токенов; (б) сокет чата захватывает токен при подключении —
    через 15 минут вызовы инструментов падают с 401, нужно переподключение; (в) нет ErrorBoundary —
    любое исключение рендера даёт белый экран (уже случалось дважды); (г) `ImportPage` рендерит все
    строки предпросмотра (для реального файла срезов — свыше 12 тыс. строк); (д) фильтр периода
    в карточке — точное совпадение на сервере, а выглядит как поиск по подстроке; (е) карточка не
    показывает `dynamicsCategory`/`previousNotTransferred`, хотя API их отдаёт (критерий ТЗ
    «динамика относительно предыдущего среза»); (ж) в проекте нет ESLint, Prettier и тестов.
    Целевое решение: переиспользовать `refreshSingleFlight` в AI-клиенте (`getFreshAccessToken()`),
    переподключение со свежим токеном, ErrorBoundary, пагинация/лимит таблиц предпросмотра,
    выбор периода из списка, колонка динамики, минимальный линт и юнит-тесты.

57. **Экспорт xlsx в kcr-ml: формула-инъекция.** `OpenPyxlExporter` записывает значения как есть,
    а openpyxl трактует строку, начинающуюся с `=`, как формулу — название или комментарий клиента
    из БД (и произвольный `table`-payload от клиента) превращается в формулу при открытии файла.
    Целевое решение: нейтрализовать ведущие `=`, `+`, `-`, `@` у строковых ячеек.

58. **Управление пользователями.** Кроме `GET /users` (админ) и bootstrap ничего нет: заблокировать
    оператора, сменить пароль или роль, удалить пользователя невозможно (FK от `refresh_tokens`,
    `clients.assignee_id`, `client_events.author_id`, `call_list_items.created_by_id` запрещают
    удаление) — скомпрометированный аккаунт действует вечно. Целевое решение: `users.enabled` +
    проверка в `CustomUserDetails.isEnabled` (и в кэше конвертера), админ-эндпоинты
    включить/выключить/роль/сброс пароля, смена своего пароля, ограничение символов username
    (`[a-zA-Z0-9._-]`), `@LastModifiedDate` у `users`.

59. **Инфраструктура стенда и поставки.** (а) нет CI — `./gradlew test`, `pytest`, `npm run build`
    нигде не выполняются автоматически, красные тесты п. 38 остались незамеченными; (б) в compose нет
    healthcheck у `app`/`ml`/`frontend` и политик `restart`, `ml` стартует сразу за `app`, не дожидаясь
    готовности; (в) образы не зафиксированы: `ollama/ollama:latest`, `nginx:alpine`, `node:22-alpine`,
    `ghcr.io/astral-sh/uv:latest` в Dockerfile kcr-ml — сборки невоспроизводимы; (г) в JDBC-URL
    compose нет `reWriteBatchedInserts=true` (многострочные INSERT — основной выигрыш `batch_size`
    для Postgres); (д) у бэкенда нет actuator/health (п. 19), `/health` kcr-ml не отражает
    доступность бэкенда; (е) комментарий в `docker-compose.yml` («…если таблица пользователей пуста»)
    неточен — админ создаётся, когда нет ни одного ADMIN; (ж) в корне репозитория лежит
    незакоммиченный `REVIEW_PROMPT_FOR_CLAUDE.md` с устаревшей копией кода. Целевое решение: CI с
    тремя job'ами, healthcheck + `restart: unless-stopped`, зафиксированные теги/digest, параметр
    URL, удалить файл.

### P3 — качество (ревью 2026-11-28)

60. **Стиль кода.** AGENTS.md §2 требует скобки в стиле Аллмана; фактически стиль смешанный —
    Аллман в `WorklistService`, `WorklistController`, `CallListItemRepository`, `DynamicsService`,
    record'ах и сущностях, K&R — в `AuthController`, `SecurityConfig`, `ClientService`,
    `ClientController`, `StagePolicy`, сервисах `events/` и `imports/`; комментарии в одном пакете
    вперемешку русские и английские (в `imports` — английские, в `clients` — русские). Целевое
    решение: выбрать один стиль и привести код механически (Spotless — п. 21) либо поправить
    AGENTS.md; выбрать один язык комментариев.

61. **Мёртвый и дублирующий код.** `RefreshTokenRepository.deleteByTokenHash` (не используется),
    `OpenPyxlExporter.build_exporter` и `TablePayload.row_width_error` (kcr-ml, не используются),
    `POOL_MIN_PERCENT` (kcr-ml), параметр `admin` в `WorklistService.clearCallList` игнорируется,
    `SnapshotResponse.from` дублирует `DynamicsService.calculatePercent`, лишние импорты своего
    пакета (`ErrorResponse`, `GlobalExceptionHandler`), аннотации `@Index` на сущностях расходятся
    с миграциями (`idx_client_events_client_created` в V9 — с `DESC`, в аннотации — без).
    Целевое решение: удалить, аннотации привести к миграциям.

62. **Цикл пакетов `clients ↔ snapshots`.** `DynamicsService` и `DocumentTransferSnapshot`
    импортируют `clients.model.Client`, а `clients` импортирует `snapshots` (репозиторий, DTO,
    сервис). Целевое решение: `DynamicsService` принимает `Instant clientCreatedAt` вместо `Client`
    (цикл остаётся только на уровне FK сущности — это допустимо), правило направления — в AGENTS.md.

63. **Мелкие дефекты.**
    - Параметр `search` в `GET /clients` и списке на обзвон не экранирует `%` и `_` — `search=%`
      возвращает всех (проверено).
    - `WorklistFilterRequest` не валидируется: отрицательный `minDebt`, порог свыше 100 принимаются.
    - `ClaimService`: правка комментария претензии не логируется, переименование санкции теряет
      старое название в событии, переходы статусов не проверяются (цепочка из комментария V10 —
      только комментарий), номер претензии исправить нельзя (иммутабелен, удаления нет).
    - Фронтенд: классы `btn-sm` и `checkbox-label` (`CallListPage`) не определены в `styles.css`;
      классы `event-<тип>` без стилей; в `AiPrioritizePage` чипы для значений `false` никогда
      не показываются; `BriefSection` кэширует сводку до перезагрузки; `TodayPage` называет
      пустой список «просроченных», хотя включает и «на сегодня».
    - kcr-ml: тело `INTERNAL_ERROR` без `timestamp`; неизвестные маршруты отдают стандартный
      `{"detail": …}` FastAPI вместо контракта; предупреждение pytest-asyncio о не заданном
      `asyncio_default_fixture_loop_scope`; `get_today` внедряемая зависимость, но WS-эндпоинт
      вызывает `date.today()` напрямую.
    - `WorklistServiceTest` компилируется с замечанием об unchecked-операциях.
    - На этапе тестов данные клиентов (ИНН, контакты, комментарии звонков) уходят стороннему
      LLM-провайдеру — в эксплуатации только локальная модель либо согласование провайдера.

### Ранее: P2 — качество и готовность к нагрузке (ревью 2026-10-24/29)

Статусы сверены с кодом 2026-11-28 и дописаны в конце каждого пункта.

18. **Тесты.** Интеграционные тесты требуют локальный PostgreSQL — перевести на Testcontainers
    (`@ServiceConnection` + `PostgreSQLContainer`), CI станет герметичным. В
    `AuthFlowIntegrationTest` заменить парсинг JSON регулярками на JsonPath. Добавить слайсы
    `@WebMvcTest` (контроллеры) и `@DataJpaTest` (репозитории), AssertJ для ассертов, JaCoCo-порог
    на изменённый код. *Статус 2026-11-28:* открыто; JsonPath так и не введён, тесты идут в общую
    dev-БД и при падении посреди сценария оставляют там пользователей (сейчас 13 тестовых аккаунтов,
    включая ADMIN-роль) — плюс сами красные из-за п. 38.
19. **Наблюдаемость.** Логгеров в коде почти нет: добавить SLF4J в сервисы, аудит
    security-событий (login success/fail с username и IP). Подключить
    `spring-boot-starter-actuator` + Micrometer (экспорт в Prometheus); далее — JSON-логи
    (logstash-logback-encoder) и correlation-id. *Статус 2026-11-28:* открыто (логи — только
    в `GlobalExceptionHandler`, `RefreshTokenCleanupJob`, `AdminBootstrap`); без actuator нет
    и healthcheck для compose (п. 59).
21. **Сборка/CI/рантайм.** Spotless (palantir-java-format) + Error Prone (+ NullAway);
    GitHub Actions на PR (build + тесты); Dependabot/Renovate для зависимостей; переход на
    Java 21 LTS и `spring.threads.virtual.enabled=true` (виртуальные потоки для IO-bound
    нагрузки). *Статус 2026-11-28:* открыто; CI отсутствует полностью (п. 59).
33. **Bulk-удаления и батчинг вставок.** Производные `deleteBy…` Spring Data выполняют SELECT
    и построчные DELETE: logout и reuse-detection отзывают все токены пользователя по одному,
    ночная чистка сначала грузит в память все истёкшие строки. Перевести на
    `@Modifying @Query("delete from RefreshToken t where …")` (+ `clearAutomatically = true`),
    чистку — порциями (цикл с LIMIT), не одной длинной транзакцией; заодно удалить неиспользуемый
    `RefreshTokenRepository.deleteByTokenHash`. Для предстоящего upsert-импорта (§11.8, ~6k
    клиентов и ~12k срезов за файл) заранее включить батчинг: `hibernate.jdbc.batch_size` +
    `order_inserts`/`order_updates` + `reWriteBatchedInserts` драйвера Postgres; сам upsert —
    `INSERT … ON CONFLICT (kcr_code) DO UPDATE` (native) либо пакетный `JdbcTemplate.batchUpdate`.
    *Статус 2026-11-28:* батчинг вставок включён (2026-11-21: `batch_size=50`, `order_inserts`/
    `order_updates`, `saveAll`, sequence-генерация id — с дефектом п. 38); bulk-удаления токенов,
    порционная чистка, `deleteByTokenHash`, `reWriteBatchedInserts` и upsert без SELECT+UPDATE —
    открыто.
34. **Индексы под поиск клиентов.** Поиск `lower(kcr_code) LIKE '%q%' OR lower(inn) LIKE '%q%'`:
    ведущий `%` и `lower()` не используют индексы (уникальный индекс `kcr_code` неприменим, по
    `inn` индекса нет) — при росте таблицы это первый seq scan в API. Код KCR и ИНН —
    идентификаторы: искать по префиксу/точно с функциональными индексами
    `(lower(kcr_code)) varchar_pattern_ops` и `(inn) varchar_pattern_ops`; для поиска по
    подстроке — расширение `pg_trgm` + GIN-индекс `gin_trgm_ops`. Одновременно read-методы
    сервисов пометить `@Transactional(readOnly = true)`. *Статус 2026-11-28:* read-методы
    помечены; индексы и экранирование LIKE-подстановок (п. 63) — открыто; N+1 по ответственным —
    п. 49.
35. **Антибрутфорс: покрытие и доверие X-Forwarded-For.** Бакет на пару username+IP не
    сдерживает массовую регистрацию с одного IP (каждый новый username — новый бакет), а при
    прямом доступе без доверенного прокси XFF подделывается и IP-часть ключа фиктивна
    (комментарий в `AuthController` это признаёт). Добавить второй бакет IP-only на `register`
    (ключ — только IP, тот же Bucket4j), закрыть им же `/refresh` (перебор UUID нереален, но
    запрос дешёвый и безлимитный), доверие к XFF — явным конфигурационным флагом (по умолчанию
    выключено, свойство вида `app.security.trust-forwarded-for`); параметры — в конфигурацию по
    образцу п. 25. При появлении второго инстанса — общий store (Redis), как отмечено в §5.
    *Статус 2026-11-28:* открыто; за nginx стенда лимит фактически обходится (п. 46).
36. **Отзыв семейства refresh-токенов, а не всех токенов пользователя.** Повторное
    предъявление одного украденного ротированного токена сейчас отзывает все refresh-токены
    пользователя — страдают все устройства, хотя скомпрометировано одно семейство выдачи.
    Завести колонку `family_id` (корень — выдача в login; токены ротации наследуют семейство)
    с индексом; при reuse — `deleteByFamilyId` вместо `deleteByUser`. Атомарность не меняется:
    FOR UPDATE по-прежнему на строке предъявленного токена, удаление семейства — индексное.
    *Статус 2026-11-28:* открыто.

### Ранее: P3 — эволюция (ревью 2026-10-24/29)

22. **OpenAPI-контракт.** springdoc-openapi для описания `/api/v1/**` и генерации TS-клиента
    для `client/` (префикс `/api/v1/**` введён 2026-10-27 вместе с первым предметным эндпоинтом).
    *Статус 2026-11-28:* открыто; контракт kcr-ml описан вручную в его ARCHITECTURE.md.
24. **Сущности.** `Long id` вместо примитива `long` (новые сущности заводятся сразу на `Long`,
    например `Client`; остались `User` и `RefreshToken`); `equals`/`hashCode` по id; аудит-поля
    (`@EnableJpaAuditing`, `@CreatedDate`/`@LastModifiedDate`); `@Version` при первом сценарии
    конкурентных правок. *Статус 2026-11-28:* `@EnableJpaAuditing` + `@CreatedDate` сделаны
    (2026-11-08, `JpaAuditingConfig`; `@LastModifiedDate` — нет); остальное открыто; взятие
    клиента защищено условным UPDATE, а не `@Version`.
25. **Конфигурация.** `@ConfigurationProperties`-record (`JwtProperties`) вместо `@Value`-полей
    в `JwtService`/`RefreshTokenService`. *Статус 2026-11-28:* открыто (`@Value` также в
    `AuthRateLimiter`, `AccountLockoutService`, `AdminBootstrap`, `SecurityConfig`).
26. **Маппинг и структура пакетов.** MapStruct (compile-time, без рефлексии) для entity ↔ DTO,
    когда маппинг станет объёмным. *Статус 2026-11-28:* переход на package-by-feature выполнен
    полностью 2026-11-08 (PR #10, §3/§11.5) — из пункта осталось только MapStruct; цикл пакетов
    `clients ↔ snapshots` — п. 62.
37. **Эксплуатационный минимум рантайма.** Явная настройка HikariCP под инстанс Postgres
    (`maximum-pool-size`, `connection-timeout`, `leak-detection-threshold`), graceful shutdown
    (`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`) — деплой без
    обрыва обрабатываемых запросов, cron джобов — в явной зоне (`@Scheduled(zone = "UTC")`;
    сейчас зона сервера). Дополняет п. 19 (actuator/метрики). *Статус 2026-11-28:* открыто;
    зона «сегодня» — п. 54.

### Закрыто

Пункты 1–17, 20, 23, 27–32 (вся прежняя группа P1 «до первого продакшена») — закрыты в
2026-10-25 … 2026-11-03, записи — в хронологии §9.

---

## 11. Требования из ТЗ и план домена (разбор ТЗ 2026-10-27)

Источник — техническое задание заказчика «ТЗ: сервис контроля передачи оригиналов документов
и коммуникации с клиентами КЦР» (команда Корпоративного центра регистрации СКБ Контур). Раздел —
план: по мере реализации его пункты переносятся в §3 (структура пакетов), §4 (доменная модель)
и §9 (текущее состояние), здесь остаётся только невыполненное. Терминология: в ТЗ продукт
называется «КЦР», в проекте закреплён латинский вариант KCR (`kcrCode`) — это один и тот же
идентификатор клиента, отдельное поле «код КЦР» не заводится.

### 11.1. Границы MVP

Входит (критерии готовности MVP из ТЗ):

- импорт базы клиентов из Excel — upsert по коду KCR без дублей; отсутствие клиента в файле
  не удаляет его из системы;
- импорт срезов состояния передачи документов за выбранный период и дату; предыдущие срезы
  сохраняются, никогда не перезаписываются;
- карточка клиента: текущее количество непереданных документов, история его изменения, динамика
  относительно предыдущего среза;
- фильтрация клиентов и формирование списка для работы операторов;
- атомарное взятие свободного клиента оператором (один клиент — один ответственный, если
  администратор явно не переназначил);
- сохранение результата звонка: комментарий, договорённость, срок со стороны клиента, дата
  следующего контакта;
- единая история каждого действия с датой, временем и автором;
- список клиентов, по которым наступил срок следующего действия;
- претензии и санкции (добавляет администратор);
- поиск клиента по коду KCR или ИНН;
- разграничение ролей: оператору доступны только функции работы с клиентами, административные —
  только администратору; роли `ADMIN`/`OPERATOR` соответствуют ролям ТЗ, администратор наследует
  возможности оператора;
- ИИ-помощник (входит в критерии готовности MVP): отвечает на вопросы по данным сервиса, формирует
  сводку по клиенту перед звонком и приоритетные выборки; строго read-only — не меняет данные,
  статусы, этапы и ответственных; приёмка — по контрольным сценариям.

Статус критериев на 2026-11-28 (✅ — работает; ⚠️ — работает с оговорками, указан пункт §10):

| Критерий | Статус |
|---|---|
| Импорт клиентов из Excel (upsert, без удаления отсутствующих) | ✅ (шум событий — п. 50) |
| Импорт срезов за период и дату, предыдущие сохраняются | ✅ (идемпотентность — п. 51; источник итога — п. 39) |
| Карточка: количество непереданных, история изменения, динамика к предыдущему срезу | ⚠️ срезы и история есть, колонка динамики в карточке не выводится (п. 56), сама категория — с оговорками (п. 40, 42) |
| Фильтрация клиентов и формирование списка для операторов | ⚠️ список на обзвон есть; в общем списке клиентов нет долговых колонок и фильтров ТЗ (п. 52, остаток шага 4 §11.8) |
| Атомарное взятие (один клиент — один ответственный) | ✅ (границы — п. 44) |
| Сохранение результата звонка (комментарий, договорённость, сроки, следующий контакт) | ✅ |
| Единая история каждого действия с датой, временем и автором | ⚠️ ручные правки карточки и составление списка на обзвон не фиксируются (п. 52, 53) |
| Список клиентов с наступившим сроком следующего действия | ✅ (часовой пояс — п. 54) |
| Претензии и санкции (админ) | ✅ |
| Поиск по коду KCR или ИНН | ✅ |
| Разграничение ролей ADMIN/OPERATOR | ⚠️ в целом да; границы прав оператора и открытая регистрация — п. 44, 45 |
| ИИ-помощник (read-only, вопросы, сводка, приоритетные выборки, контрольные сценарии) | ⚠️ функционально готов; ограничение в 500 клиентов и дубль правил — п. 41, 42; сценарии живой моделью не прогнаны |

Не входит (архитектурно оставить место):

- шаблоны писем и генерация текста письма по шаблону — в MVP только ручное событие
  «Письмо отправлено»;
- интеграция с почтой — оператор копирует текст и отправляет через существующую почту;
- автоматическая отправка претензий — прямо исключена ТЗ;
- BI-дашборды и графики — достаточно таблицы, фильтров, счётчиков и выборок через ИИ-помощника;
- прямые интеграции с внутренними системами Контура — вход только через Excel; при этом
  Excel-импорт обязан быть заменимым на API-интеграцию без изменения модели данных (§11.6).

Спорные места ТЗ и решения по ним:

- ссылка на запись разговора заявлена и «основным сценарием», и «можно не делать» — делается
  сразу: это строковое поле `record_url` у события;
- ручное добавление ответа клиента из почты — покрывается событием-комментарием;
- «новичок»: в справочнике типов есть `ClientType.NEWCOMER` (из выгрузки), а в правилах динамики
  новичок — вычислимый признак «подключился < 6 месяцев назад»; для правил используется
  вычислимый по `clients.created_at` (см. §11.3), сверка с типом из выгрузки — вопрос 2 §11.7.
  Момент появления строки в этой БД не равен дате подключения клиента — последствия и решение
  в п. 40 §10.

### 11.2. Бизнес-правила

- **Отчётные периоды и срезы.** Период — квартал; срез фиксируется после первых 10 рабочих дней
  следующего за кварталом месяца, далее динамика анализируется раз в 1–4 недели (после
  автоматизации — раз в неделю). По одному периоду — несколько срезов. На один сертификат —
  2 документа (заявление и лист ознакомления), каждый считается отдельно: `total_documents` —
  число документов, а не сертификатов.
- **Порог 5%.** Клиент не включается в список на обзвон при доле непереданных < 5%, кроме
  «новичков». Это правило формирования пула, а не запрет на звонок: итоговое решение —
  за администратором. На реальных выгрузках доля непереданных сейчас неизвестна (п. 39 §10),
  поэтому правило фактически не применяется.
- **Категории динамики** — вычисляются при импорте среза (относительно предыдущего среза того же
  периода и истории периодов) и сохраняются в срезе; текущая категория клиента — категория
  последнего среза. Как правило реализовано на 2026-11-28 — §4, `DynamicsService` (без разбивки
  по периодам; расхождение — п. 42 §10):

| Категория (`DynamicsCategory`) | Правило |
|---|---|
| `NO_DEBT` — все переданы | `not_transferred_documents = 0`, работы не требует |
| `FIRST_OCCURRENCE` — впервые появились | за прошлые периоды всё передано, за последний — долг (в т.ч. частичный) |
| `DECREASING` — уменьшается | меньше, чем в предыдущем срезе |
| `UNCHANGED_OR_GROWING` — не меняется/растёт | не уменьшился относительно предыдущего среза |
| `REAPPEARED` — повторное появление | цикл был завершён (всё передано), долг появился снова |
| `PERSISTENT` — сохраняется длительно | непереданные в нескольких периодах подряд |
| `NEWCOMER_RISK` — долг у новичка | подключение < 6 месяцев назад и есть долг (в т.ч. < 5%) |

- **Этапы работы.** Выявлены непереданные → первый звонок → ожидание согласованного срока →
  повторная проверка → второй звонок → ожидание → претензионная работа → работа с претензией
  и санкциями → завершение цикла. Этап работы и состояние передачи документов хранятся отдельно
  (прямое требование ТЗ); последовательность может отличаться по клиентам, циклы повторяются
  в следующих периодах.
- **Два срока договорённости.** Срок со стороны клиента (когда обещал передать) и дата следующего
  контакта (когда связываться) — оба фиксируются в событии звонка; второй обновляет
  `clients.next_contact_at`.
- **Идентификация клиентов.** Уникален только код KCR. ИНН обязателен, но не уникален: один ИНН
  с разными КПП и кодами KCR — разные клиенты; перезаключение договора = новый код KCR = новый
  клиент. Две строки с одним кодом KCR внутри одного файла — ошибка импорта с указанием причины.
- **Конкурентность взятия в работу.** Два оператора не могут одновременно взять одного клиента:
  условный UPDATE (`... WHERE assignee_id IS NULL`) либо `@Version` (п. 24 §10).
- **Аудит.** Любая мутация (звонок, письмо, комментарий, смена этапа, переназначение, загрузка
  данных, претензия, санкция) создаёт запись в истории клиента. История выдаётся хронологически
  от новых событий к старым.

### 11.3. Целевая модель данных

Доработка `clients` (миграция `V8__client_work_fields.sql`, реализовано 2026-11-08):

| Поле | Тип | Назначение |
|---|---|---|
| stage | enum `WorkStage`, not null (default `NOT_IN_WORK`) | этап работы — отдельно от состояния документов |
| assignee_id | FK `users`, nullable | ответственный оператор; null — не взят в работу |
| next_contact_at | DATE, nullable | следующий контакт (обновляется событием звонка) |
| created_at | TIMESTAMP, not null | появление в сервисе — база правила «новичок < 6 месяцев» |

Доработка `document_transfer_snapshots` и `call_list_items` (миграция `V11__snapshot_dynamics_and_call_list.sql`, реализовано 2026-11-19):

| Поле | Тип | Назначение |
|---|---|---|
| dynamics_category | enum `DynamicsCategory`, nullable | категория динамики, вычисляется при импорте |
| previous_not_transferred | INT, nullable | значение предыдущего среза («изменение») — денормализация под списки |

Новая сущность `client_events` (реализована миграцией `V9__client_event.sql`, 2026-11-08) —
единая история клиента (сценарии 6, 9, 10, 12 ТЗ), см. §4:

| Поле | Тип |
|---|---|
| id, client_id FK, author_id FK `users`, created_at | служебные |
| type | enum `ClientEventType`: `CALL`, `LETTER_SENT`, `COMMENT`, `STAGE_CHANGED`, `ASSIGNEE_CHANGED`, `IMPORT`, `CLAIM`, `SANCTION` |
| comment | текст, общий для всех типов |
| call_result | enum `CallResult`, только для `CALL` |
| agreement | текст договорённости, только для `CALL` |
| client_deadline | DATE — срок со стороны клиента |
| next_contact_at | DATE — дата следующего контакта |
| record_url | ссылка на запись разговора |

Новые сущности `claims` и `sanctions` (реализованы миграцией `V10__claim_sanction.sql`,
2026-11-08, см. §4): претензия — client FK, номер, дата, номер связанного инцидента, дата
получения клиентом, статус `ClaimStatus`, комментарий; санкция — claim FK, название, статус
применения `SanctionStatus`. Мутации претензий и санкций дублируются событиями
`CLAIM`/`SANCTION` в общей истории клиента.

`letter_templates` в MVP не включается (§11.1); при появлении — тривиальная таблица, модель
не меняет.

Enum'ы — все `@Enumerated(STRING)`, varchar + CHECK по списку значений (как `ClientType`):
`WorkStage` (`NOT_IN_WORK → IN_POOL → FIRST_CALL → WAITING_CLIENT_DEADLINE → RECHECK →
SECOND_CALL → CLAIM_WORK → COMPLETED`; `IN_POOL` — «администратор отобрал в общий пул, оператор
ещё не взял»; набор принят, правила смены — по ролям, см. §4), `DynamicsCategory` (§11.2,
реализована 2026-11-19 — `snapshots/DynamicsService`), `ClientEventType`, `CallResult`,
`ClaimStatus`, `SanctionStatus` (справочники приняты 2026-11-08, см. §4).

Генерация первичных ключей — §4: для трёх крупных таблиц с `V12` работают sequence-ы с шагом 50
(ради батчинга при импорте), для остальных — `IDENTITY`.

### 11.4. REST API (реализовано на 2026-11-28)

Префикс `/api/v1/**`. Все эндпоинты, кроме отмеченных как публичные, требуют аутентификации;
доступ — `@PreAuthorize` по ролям (`SecurityConfig` гарантирует «по умолчанию закрыто»).
Тело ошибок — единый контракт §6.

Аутентификация и пользователи:

| Метод и путь | Доступ | Назначение |
|---|---|---|
| `POST /api/v1/auth/register` | публичный | самостоятельная регистрация (роль `OPERATOR`) — п. 45 §10 |
| `POST /api/v1/auth/login` | публичный | вход, пара токенов (§5) |
| `POST /api/v1/auth/refresh` | публичный | ротация refresh-токена с reuse-detection (§5) |
| `POST /api/v1/auth/logout` | аутентифицирован | отзыв всех refresh-токенов пользователя, 204 |
| `GET /api/v1/users` | админ | справочник пользователей (id, username, role) для переназначения |

Клиенты и работа с ними:

| Метод и путь | Доступ | Назначение |
|---|---|---|
| `GET /api/v1/clients` | обе | страница: `search` (код KCR/ИНН, подстрока), `clientType`, `kcrActive`, `kcrBlocked`, `flag`, `stage`, `assigneeId`, `unassigned`, `page`/`size` (≤ 100), `sortBy` (`id`, `kcrCode`, `inn`, `organizationName`, `stage`, `nextContactAt`), `sortDir` |
| `GET /api/v1/clients/{id}` | обе | карточка `ClientResponse`: поля клиента, этап, ответственный, `nextContactAt`, `createdAt` |
| `POST /api/v1/clients` | админ | создание карточки; дубль кода KCR → 409 |
| `PATCH /api/v1/clients/{id}` | админ | правка карточки, «null = не менять», `kcrCode` неизменяем; историю не пишет (п. 53 §10) |
| `POST /api/v1/clients/{id}/take` | обе | атомарно взять свободного клиента (условный UPDATE) |
| `PATCH /api/v1/clients/{id}/assignment` | админ | `assigneeId` — назначить; `null` — освободить |
| `PATCH /api/v1/clients/{id}/stage` | обе | смена этапа по `StagePolicy` (оператор — рабочие этапы своих клиентов), необязательный `comment` |
| `GET /api/v1/clients/{id}/snapshots` | обе | срезы по датам; необязательный `reportingPeriod` — точное совпадение |
| `GET /api/v1/clients/{id}/events` | обе | история, новые → старые, пагинация |
| `POST /api/v1/clients/{id}/events` | обе | ручное событие `CALL`/`LETTER_SENT`/`COMMENT`; `CALL` требует `callResult`, может задать `nextContactAt` и сменить `stage` (владелец клиента не проверяется — п. 44 §10) |
| `GET /api/v1/clients/{id}/claims` | обе | претензии с вложенными санкциями |
| `POST /api/v1/clients/{id}/claims` | админ | регистрация претензии |
| `PATCH /api/v1/claims/{id}` | админ | `status`, `receivedByClientAt`, `incidentNumber`, `comment` (null = не менять) |
| `POST /api/v1/claims/{id}/sanctions` | админ | добавление санкции |
| `PATCH /api/v1/sanctions/{id}` | админ | `status`, `name` (null = не менять) |

Рабочие списки:

| Метод и путь | Доступ | Назначение |
|---|---|---|
| `GET /api/v1/worklist/today` | обе | «сегодня нужно»: `next_contact_at <= сегодня`; оператору — свои, админу — все; пагинация |
| `POST /api/v1/worklist/preview` | админ | предпросмотр списка на обзвон по `WorklistFilterRequest` (`search`, `clientType`, `hasDebt`, `minDebt`, `minUntransferredPercent`, `maxUntransferredPercent`, `lowDynamics`, `dynamicsCategory`, `clientIds`); ответ — список `CallListClientResponse` без пагинации |
| `POST /api/v1/worklist/generate` | админ | заменяет закреплённый список отобранными клиентами (`NOT_IN_WORK` → `IN_POOL` + событие), возвращает сводку `CallListSummaryResponse` |
| `GET /api/v1/worklist/call-list` | обе | закреплённый список: позиции, кто и когда составил |
| `DELETE /api/v1/worklist/call-list` | админ | очистить список, 204 |

Импорт (multipart, часть `file`, до 10 МБ; Apache POI; парсеры-адаптеры — §3, §11.6):

| Метод и путь | Доступ | Назначение |
|---|---|---|
| `POST /api/v1/import/clients/preview` | админ | валидация без записи: `toCreate`, `toUpdate`, `issues` (лист/строка/колонка/причина) |
| `POST /api/v1/import/clients` | админ | upsert по коду KCR (patch-семантика обновления); отчёт `created`/`updated`/`issues`; событие `IMPORT` на каждого затронутого клиента |
| `POST /api/v1/import/snapshots/preview` | админ | query-параметры `snapshotDate` (ISO-дата) и `reportingPeriod`; импортируются только листы с совпадающим именем; отчёт `rows`, `unmatchedKcrCodes`, `skippedSheets`, `issues` |
| `POST /api/v1/import/snapshots` | админ | применение: новые строки срезов (итог наследуется, категория динамики считается), событие `IMPORT` на каждый срез; 400 `IMPORT_PERIOD_NOT_FOUND`, если лист периода не найден |

Запланированные, но не реализованные: `GET /api/v1/worklist/available` (свободные клиенты пула —
пока оператор берёт из `GET /worklist/call-list`) и `GET /api/v1/stats/summary` (счётчики по
категориям динамики — сейчас kcr-ml считает их сам, п. 41 §10). Долговые колонки и фильтры ТЗ
в `GET /clients` (состояние передачи, количество/доля непереданных, наличие претензии, период,
новое/повторное появление) — остаток шага 4 §11.8.

ИИ-помощник. Планировавшиеся здесь `POST /api/v1/ai/questions` и `GET /api/v1/clients/{id}/brief`
реализованы в kcr-ml под префиксом `/api/v1/ai/**` (полный контракт — ARCHITECTURE.md kcr-ml, §7):
чат — `WS /api/v1/ai/chat`, сводка перед звонком — `GET /api/v1/ai/clients/{id}/brief`,
приоритизация — `POST /api/v1/ai/prioritize`, аналитика —
`GET /api/v1/ai/analytics/{summary,dynamics,top-debt,operators}`, экспорт —
`POST /api/v1/ai/export/xlsx`. Изменяющих эндпоинтов у ИИ нет архитектурно. Kcr-ml читает этот
бэкенд шестью эндпоинтами — только GET: `/clients`, `/clients/{id}`, `/clients/{id}/snapshots`,
`/clients/{id}/events`, `/clients/{id}/claims`, `/worklist/today`.

### 11.5. Целевая структура пакетов

По п. 26 §10 при появлении второго домена — переход на package-by-feature (импорт, события,
претензии, ИИ — отдельные домены). Выполнен полностью 2026-11-08 (ветка
`refactor/package-by-feature`): домен «клиенты» — `clients/` с `events/` (вопрос 6 §11.7
закрыт), домены `auth/` и `users/` (users выделен из auth: справочник пользователей +
AdminBootstrap), `snapshots/`, сборка `imports/` целиком и контракт ошибок `common/error/`.
Актуальное дерево — §3; расхождений с ним больше нет. `ai/` заводится вместе с кодом
ИИ-фасада. Правила слоёв внутри пакетов не меняются (§3).

### 11.6. Заменяемость Excel-импорта на API

Требование ТЗ: позднее Excel-импорт заменяется API-интеграцией без изменения модели данных
и пользовательских сценариев. Следствия:

- парсинг Excel — тонкий адаптер, выдающий те же командные DTO, что выдаст будущий API-клиент:
  `ClientUpsertCommand`, `SnapshotCreateCommand`;
- upsert, категоризация, создание событий живут в сервисном слое и не знают об источнике данных;
- маппинг колонок шаблона Excel — конфигурация адаптера, а не модели.

### 11.7. Открытые вопросы к заказчику

1. ~~Точный список этапов работы и допустимые переходы — набор `WorkStage` в §11.3
   предварительный.~~ Решение по проекту 2026-11-08: набор принят, порядок переходов не
   контролируется (последовательность отличается по клиентам, §11.2); права — по ролям
   (`StagePolicy`): админ — любой этап, оператор — рабочие этапы своих клиентов. Уточнение
   списка у заказчика остаётся открытым в части формулировок. Дополнительно нужно решение по
   проекту: что происходит с этапом при взятии клиента и с ответственным при возврате клиента
   в `NOT_IN_WORK`/`COMPLETED` (п. 44 §10).
2. «Новичок»: подтверждение расчёта по дате появления в сервисе (6 месяцев) и его сверка с типом
   `NEWCOMER` из выгрузки (база — `clients.created_at`, реализовано в V8 и `DynamicsService`).
   Стало критичнее: `created_at` — момент загрузки строки в эту БД, а не подключения клиента
   (п. 40 §10) — нужно поле с датой подключения в выгрузке либо иное правило.
3. ~~Справочники: результаты звонка (`CallResult`), статусы претензии (`ClaimStatus`), доступные
   санкции и статусы их применения (`SanctionStatus`).~~ Наборы приняты 2026-11-08 (§4);
   расширение — миграцией CHECK-констрейнта. Справочник доступных санкций (названия) не
   определён — название санкции свободный текст. Открыто: обязательны ли переходы статусов
   претензии по цепочке (п. 63 §10).
4. Формат Excel-шаблонов импорта клиентов и срезов, включая исторические данные (заказчик
   приводит их к единому формату). Частично закрыт 2026-10-27 парсерами по реальным выгрузкам
   Дубны (§9): заголовок в строке 2; у клиентов коды лежат в колонке A без заголовка при пустой
   колонке «Код КЦР», карточные колонки (Название/ИНН/КПП/менеджеры) не заполнены; у срезов
   период = имя листа, дата среза — только в имени файла. Открыто: единый шаблон, источник ИНН
   и названия организации (в текущей выгрузке их нет, а `clients.inn` NOT NULL — для upsert
   потребуется решение: договорённость о заполнении шаблона или смягчение ограничения),
   исторические данные.
5. ~~ИИ-помощник: где живёт LLM (прокси-эндпоинт бекенда с ключом из окружения или отдельный сервис
   в `ml/` монорепо), модель и бюджет; состав контрольных сценариев приёмки.~~ Решено по проекту
   2026-11-08: LLM живёт в отдельном сервисе `ml/` (PR #9); на этапе тестов — API-провайдер, в
   проде — локальная модель (открытые вопросы — ARCHITECTURE.md kcr-ml, §16), состав контрольных
   сценариев — там же, §14. Открыто для заказчика: допустимость передачи данных клиентов
   стороннему провайдеру и бюджет на этапе тестов.
6. ~~(решение по проекту, не заказчику) Переход на package-by-feature (§11.5).~~ Выполнен
   полностью 2026-11-08: `clients/`, `auth/`, `users/`, `snapshots/`, `common/error/`
   (§3/§11.5).
7. *(добавлен 2026-11-28)* Откуда брать «всего документов» за период: в выгрузке срезов колонки
   нет, без неё доля непереданных и порог 5% не считаются (п. 39 §10). Варианты: колонка «Всего»
   в шаблоне срезов, импорт итогов из выгрузки клиентов, ручной ввод.
8. *(добавлен 2026-11-28)* Политика доступа: нужна ли самостоятельная регистрация операторов или
   пользователей заводит администратор (п. 45 §10); может ли оператор брать клиентов вне списка,
   составленного администратором, и фиксировать события на чужих клиентах (п. 44 §10).
9. *(добавлен 2026-11-28)* Повторный импорт того же файла за тот же период и дату — отклонять
   или заменять ранее загруженные срезы той же даты (п. 51 §10).

### 11.8. Порядок внедрения

0. **Предпосылки из §10**, критичные для ТЗ-функционала: ~~аудит-поля (`@EnableJpaAuditing`,
   `@CreatedDate`)~~ — закрыто 2026-11-08 (`JpaAuditingConfig`, V8); пп. 7, 11 и префикс п. 22 —
   2026-10-27 вместе с CRUD клиентов.
1. **Операторский цикл** — ✅ готово (2026-11-08, ветка `feature/operator-cycle`, §9):
   `V8` (поля процесса) + `V9` (`client_events`); создание и выдача событий (звонок обновляет
   `next_contact_at`/stage), `take`/`assignment`/`stage` с атомарностью взятия,
   `GET /worklist/today`; фронтенд — «Сегодня», карточка с историей и претензиями. Границы прав
   оператора — п. 44 §10.
2. **Динамика** — ✅ готово (`V11` + `DynamicsService`, 2026-11-19): детерминированный расчёт
   7 категорий (§11.2), колонки `dynamics_category` и `previous_not_transferred`, расчёт при
   импорте срезов (`SnapshotImportService`). Оговорки — п. 39, 40, 42 §10.
3. **Excel-импорт** — ✅ готово: парсеры-адаптеры клиентов и срезов (2026-10-27, пакет `imports`);
   upsert-сервисы и эндпоинты preview/применение с отчётами (`ImportController`, §11.4) —
   2026-11-04; фильтр по отчётному периоду и события `IMPORT` — 2026-11-08; пересчёт категорий —
   2026-11-19; батчевая запись — 2026-11-21. Осталось: скачиваемый файл проблемных строк,
   идемпотентность (п. 51 §10).
4. **Пул и статистика** — ⚠️ частично: единый закреплённый список на обзвон готов (`V11` +
   `call_list_items`, `POST /worklist/preview`, `POST /worklist/generate` с переводом в `IN_POOL`,
   `GET /worklist/call-list`, 2026-11-19). Остаток шага: `GET /worklist/available` (свободные
   клиенты пула), `GET /stats/summary` (счётчики по категориям динамики; нужен и kcr-ml —
   п. 41 §10), долговые колонки и фильтры ТЗ в `GET /clients`; фильтры и аудит самого списка —
   п. 52 §10.
5. **Претензии и санкции** — ✅ готово (`V10` + API, 2026-11-08, вместе с п. 1).
6. **ИИ-помощник** — ✅ готово вне этого бэкенда (сервис `ml/`, PR #9, 2026-11-08): чат, сводка,
   приоритизация, аналитика, экспорт; read-only гарантирован архитектурой kcr-ml. Остаток:
   согласовать с kcr-ml правила динамики и порога (п. 42, 55 §10) и снять ограничение в 500
   клиентов (п. 41 §10).

Тесты — по ходу каждого пункта (юнит сервисов, интеграционные сценарии; `@DataJpaTest` и
Testcontainers — п. 18 §10). Каждый пункт — отдельная ветка/PR в `develop` (конвенция
`feature/auth`); закрываемые пункты §11 переносятся в §3/§4/§9 в том же PR. Перед пушем и
слиянием — полный `./gradlew build`: к чему приводит прогон одних юнит-тестов, показывает п. 38 §10.
