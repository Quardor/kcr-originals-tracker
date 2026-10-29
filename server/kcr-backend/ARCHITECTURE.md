# ARCHITECTURE — kcr-backend

Архитектурная документация kcr-backend: назначение, стек, структура пакетов, доменная модель,
аутентификация, текущее состояние, требования из ТЗ и план домена. Любое изменение структуры
пакетов, стека или доменной модели **должно** отражаться в этом файле.

---

## 1. Назначение проекта

**kcr-originals-tracker** — монорепозиторий системы учёта оригиналов документов (KCR Originals Tracker).

`server/kcr-backend` — REST-бэкенд на Spring Boot. Отвечает за:

- аутентификацию и авторизацию (JWT: access- + refresh-токены);
- хранение пользователей и их ролей;
- хранение справочника клиентов (сущность `Client`, код KCR — естественный уникальный ключ);
- срезы состояния передачи документов по клиентам (динамика по отчётным периодам);
- (в перспективе) учёт оригиналов документов — привязка к клиентам и дальнейшие предметные
  сущности (целевой план по ТЗ — §11).

Остальные каталоги монорепо (`client/`, `ml/`) — пустые заготовки с README, полезной нагрузки пока не несут.

## 2. Технологический стек

| Компонент | Технология | Версия |
|---|---|---|
| Язык | Java (toolchain) | 17 |
| Сборка | Gradle, Kotlin DSL | 9.7.1 (wrapper) |
| Framework | Spring Boot | 4.1.1 |
| ORM | Spring Data JPA (Hibernate) | из BOM Boot |
| БД | PostgreSQL | локальный инстанс |
| Миграции | Flyway (`spring-boot-flyway` + `flyway-database-postgresql`) | из BOM Boot |
| Безопасность | Spring Security (stateless, JWT) | из BOM Boot |
| JWT | jjwt (api/impl/jackson) | 0.12.6 |
| Rate limiting | Bucket4j (`bucket4j_jdk17-core`) + Caffeine (кэш бакетов, версия из BOM Boot) | 8.21.0 |
| Бойлерплейт | Lombok | из BOM Boot |
| Excel-импорт | Apache POI (`poi-ooxml`) | 5.5.1 |
| Тесты | JUnit 5 (spring-boot-starter-*-test) | из BOM Boot |

Группа: `ru.quard0r`, корневой пакет: `ru.quard0r.kcrbackend`.

## 3. Структура пакетов (слоистая архитектура)

```
ru.quard0r.kcrbackend
├── KcrBackendApplication   — точка входа (@SpringBootApplication)
├── config/                 — конфигурация Spring (SecurityConfig: security-цепочка, CORS, бины)
├── controller/             — REST-контроллеры (AuthController: /api/v1/auth/**,
│                             ClientController: /api/v1/clients/**)
├── dto/
│   ├── request/            — входные DTO (records: LoginRequest, RegisterRequest, RefreshRequest,
│   │                         ClientCreateRequest, ClientUpdateRequest — PATCH-семантика «null = не менять»)
│   └── response/           — исходящие DTO (records + @Builder: AuthResponse, ErrorResponse,
│                             ClientResponse, SnapshotResponse, дженерик PageResponse)
├── exception/               — контракт ошибок (§6): ErrorCode — enum «код, HTTP-статус,
│                             сообщение», DomainException — единственное доменное исключение,
│                             GlobalExceptionHandler — маппинг в ErrorResponse
├── imports/                — Excel-импорт (feature-пакет §11.5; `import` — ключевое слово Java):
│                             ClientExcelParser / SnapshotExcelParser — тонкие адаптеры реальных
│                             выгрузок → командные DTO (§11.6), формат-валидация внутри; ExcelCells —
│                             чтение ячеек, нормализация заголовков, поиск колонки кода KCR;
│                             ParseResult/ParseIssue; dto/ (ClientUpsertCommand, SnapshotCreateCommand).
│                             UpsertService и REST-эндпоинты — план (§11.8)
├── model/                  — JPA-сущности (User, RefreshToken, Client, DocumentTransferSnapshot)
│                             + enum'ы (Role, ClientType)
├── repository/             — Spring Data JPA репозитории (UserRepository, RefreshTokenRepository,
│                             ClientRepository, DocumentTransferSnapshotRepository)
├── scheduling/             — фоновые задачи (RefreshTokenCleanupJob — ежедневная очистка
│                             просроченных refresh-токенов, см. §5)
├── security/               — JWT-инфраструктура (JwtService, JwtAuthenticationFilter,
│                             CustomUserDetailsService, RestAuthenticationEntryPoint)
│                             + AuthRateLimiter — антибрутфорс-лимитер login/register (см. §5)
└── service/                — бизнес-логика (AuthService, RefreshTokenService,
                              AccountLockoutService — счётчик неудачных входов и блокировка
                              аккаунта, ClientService)
```

Направление зависимостей строго сверху вниз:

```
controller → service → repository → model
     ↘ dto ↙            ↘ security ↙
```

- Контроллер не обращается к репозиториям и не содержит бизнес-логики.
- Сервис не знает о HTTP (никаких HttpStatus/RequestEntity внутри сервисов).
- Репозитории работают только с сущностями; DTO в них не передаются.

## 4. Доменная модель

Раздел описывает реализованные сущности; целевые по ТЗ (этапы работы, события истории,
претензии/санкции, категоризация динамики) — §11.

### User (`users`)
| Поле | Тип | Ограничения |
|---|---|---|
| id | long | PK, IDENTITY |
| username | String | not null, unique |
| password | String | not null (BCrypt-хеш) |
| email | String | unique, опционален |
| role | Role | not null, `@Enumerated(EnumType.STRING)` |
| failedAttempts | int | колонка `failed_attempts`, not null, default 0 — счётчик неудачных попыток входа (антибрутфорс, §5; `V6__user_account_lockout.sql`) |
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
| id | Long | PK, IDENTITY |
| kcrCode | String | not null, unique — основной идентификатор клиента |
| inn | String | not null, varchar(12) |
| kpp | String | varchar(9), опционален |
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

### ClientType (enum)
`VIP`, `TECH_PARTNER`, `WORKS_VIA_MP`, `NEWCOMER`. Хранится именем значения
(`@Enumerated(EnumType.STRING)`, varchar + CHECK — см. Role).

### DocumentTransferSnapshot (`document_transfer_snapshots`)
Срез состояния передачи документов клиента: сколько документов клиент должен был передать за отчётный
период и сколько ещё не передано, по состоянию на дату среза. Доменные правила:

- срезы только добавляются, никогда не перезаписываются: по одному отчётному периоду возможно
  несколько срезов (отслеживание динамики, например помесячно); уникальности
  (клиент, период, дата) нет — каждая загрузка создаёт новую строку;
- `totalDocuments` опционален: повторный срез может загружаться без поля «Всего документов» —
  при импорте значение копируется из последнего среза того же клиента и периода (логика —
  сервисный слой будущего импорта, репозиторийный метод `findFirst…OrderBySnapshotDateDescIdDesc`
  уже подготовлен);
- % непереданных документов — производное от `notTransferredDocuments / totalDocuments`,
  в БД не хранится, вычисляется при выдаче.

| Поле | Тип | Ограничения |
|---|---|---|
| id | Long | PK, IDENTITY |
| client | Client | @ManyToOne LAZY, not null — FK `client_id` на `clients` |
| reportingPeriod | String | not null — свободный формат («2025 год», «1 квартал 2026») |
| snapshotDate | LocalDate | not null — дата, на которую актуальны данные |
| totalDocuments | Integer | опционален; CHECK (>= 0) |
| notTransferredDocuments | Integer | not null; CHECK (>= 0 и <= totalDocuments, когда total задан) |

Индекс `idx_document_transfer_snapshots_client_period_date (client_id, reporting_period,
snapshot_date)` покрывает выборку динамики по клиенту/периоду и поиск последнего среза для
наследования total (индекс на FK создаётся явно — Postgres не строит их автоматически; то же
для `refresh_tokens.user_id`, см. выше).

## 5. Аутентификация (JWT)

Схема stateless, без сессий (`SessionCreationPolicy.STATELESS`), CSRF отключён.

- Пароли хранятся как BCrypt-хеш (`BCryptPasswordEncoder`).
- Access-токен: подпись HMAC-SHA (ключ из `jwt.secret`), subject = `user.getId()`
  (username изменяем — идентификация в токене не должна ломаться при переименовании),
  claims: `preferred_username` (бывший subject, для отображения/фронта), `email`, `roles`,
  `jti` (UUID, уникален у каждого токена — задел под серверный блок-лист); срок —
  `jwt.access-expiration` (15 мин). Токены, выданные до смены subject (раньше username),
  после выката невалидны: нечисловой subject → NumberFormatException в фильтре → 401;
  клиент обновляет пару через `/refresh` (refresh-токены от схемы access-токена не зависят).
- Refresh-токен: сырой UUID отдаётся клиенту, в БД хранится только его SHA-256-хеш,
  поиск — по хешу; срок — `jwt.refresh-expiration` (7 дней).
- `JwtAuthenticationFilter` (before `UsernamePasswordAuthenticationFilter`) читает заголовок
  `Authorization: Bearer <token>`, валидирует и заполняет `SecurityContext`; пользователь
  загружается по id из subject (`CustomUserDetailsService.loadUserById`), `isTokenValid`
  сверяет id и срок. Авто-регистрация фильтра
  в servlet-контейнере отключена через `FilterRegistrationBean` (иначе он вызывается вне security-цепочки
  и его SecurityContext затирается).
- `RestAuthenticationEntryPoint` отдаёт единый контракт ошибок неаутентифицированным запросам:
  **401** с телом `ErrorResponse` (JSON) вместо дефолтного 403.
- CORS: бин `CorsConfigurationSource` + `.cors(...)` в цепочке; источники — свойство
  `app.cors.allowed-origins` (список через запятую; в dev — `http://localhost:5173`, переопределяется
  переменной окружения `APP_CORS_ALLOWED_ORIGINS`). Разрешены методы GET/POST/PUT/PATCH/DELETE/OPTIONS
  и заголовки `Authorization`, `Content-Type`; credentials не включены (токен — в заголовке). Пустой
  список (по умолчанию) — кросс-доменные запросы не разрешены, same-origin работает без ограничений.
- Публичные маршруты: `/api/v1/auth/**` — кроме `/api/v1/auth/logout` (матчер `authenticated()`
  в `SecurityConfig` стоит раньше permitAll); всё остальное — только аутентифицированным.
- Включена method security (`@EnableMethodSecurity`) — можно использовать `@PreAuthorize` по ролям.
- `CustomUserDetailsService` возвращает `CustomUserDetails` — record-обёртку над сущностью `User`
  (роль → authority `ROLE_<ROLE>`); она же — principal в `SecurityContext` после фильтра и в
  результате `authenticate(...)`.

Сценарии (реализованы в `AuthService`; `register`/`login`/`logout` — `@Transactional`, `refresh` — нет,
см. ниже):
- `register` — проверка уникальности username и email (нормализованного: blank → null) →
  сохранение (BCrypt, роль OPERATOR) → выдача пары токенов. Конкурентный конфликт уникальности в БД
  ловится `GlobalExceptionHandler` как 409 — с точным кодом по имени констрейнта (§6).
- `login` — через `authenticationManager.authenticate(...)` (бин в `SecurityConfig`;
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
   `X-Forwarded-For`, при отсутствии заголовка `remoteAddr`). Переполнение →
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
   `AccountLockoutService.registerFailedLogin` в отдельной транзакции (`REQUIRES_NEW`):
   внешний `login` помечен `@Transactional` и откатывается по 401 — без отдельной транзакции
   откат стирал бы инкремент (тот же принцип, что у refresh-ротации: мутация-последствие живёт
   вне откатывающейся транзакции, см. `refresh` выше).

Очистка: просроченные токены (в том числе уже ротированные) удаляет `RefreshTokenCleanupJob`
(`scheduling/`, `@EnableScheduling` на `KcrBackendApplication`) — `@Scheduled`-cron
`app.cleanup.refresh-token-cron` (по умолчанию `0 0 4 * * *`, локальная зона сервера) вызывает
`RefreshTokenService.deleteExpired()` → `deleteByExpiryDateBefore(now)`; без job таблица
`refresh_tokens` росла бы бесконечно.

## 6. Исключения и контракт ошибок

Единая точка правды об ошибках API — enum `ErrorCode` (пакет `exception/`): каждый код несёт
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
| `VALIDATION_FAILED` | 400 | `MethodArgumentNotValidException` (Bean Validation DTO) |
| `MALFORMED_REQUEST_BODY` | 400 | `HttpMessageNotReadableException` (невалидный JSON) |
| `INVALID_REQUEST_PARAMETER` | 400 | `MethodArgumentTypeMismatchException` (query/path-параметр) |
| `RESOURCE_NOT_FOUND` | 404 | `NoResourceFoundException` (несуществующий маршрут) |
| `DATA_CONFLICT` | 409 | нарушение целостности БД по неизвестному констрейнту |
| `SHA256_UNAVAILABLE` | 500 | JRE без SHA-256 (недостижимо на стандартном JRE) |

- `DataIntegrityViolationException` (гонка «exists-проверка + save» при конкурентной записи)
  различается по имени нарушенного констрейнта: Hibernate
  `ConstraintViolationException#getConstraintName`, при отсутствии обёртки в цепочке причин —
  парсинг сообщения драйвера; `uk_users_username` → `USERNAME_ALREADY_TAKEN`,
  `uk_users_email` → `EMAIL_ALREADY_TAKEN`, `uk_clients_kcr_code` → `CLIENT_ALREADY_EXISTS`,
  прочее/неизвестное → `DATA_CONFLICT` (всё — 409; до п. 14 любое нарушение отвечало
  «Username or email already taken»).
- Сервисы бросают только `DomainException`; маппинг в HTTP — исключительная зона ответственности
  хендлера. Неаутентифицированным `RestAuthenticationEntryPoint` отдаёт тот же контракт
  (`AUTHENTICATION_REQUIRED`).

## 7. Конфигурация

Конфигурация — `src/main/resources/application.yaml` с профилями `dev`/`prod`
(2026-10-26, закрыт п. 5 §10 — секреты вынесены из VCS):

- `application.yaml` — общая часть: имя приложения, JPA (`ddl-auto=validate`, OSIV выключен),
  сроки токенов; `spring.profiles.default=dev` — без явного профиля (`bootRun`, тесты)
  активен `dev`.
- `application-dev.yaml` — локальная БД (`jdbc:postgresql://localhost:5432/postgres`,
  пользователь по умолчанию `myuser`); секреты — из окружения либо `.env` (см. ниже).
- `application-prod.yaml` — подключение (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) и
  `JWT_SECRET` строго из окружения, значений по умолчанию нет: при незаданных переменных
  приложение не стартует (fail-fast).

Секреты в репозитории не хранятся и подставляются плейсхолдерами из переменных окружения:

| Переменная | Профили | Назначение |
|---|---|---|
| `DB_PASSWORD` | dev, prod | пароль БД |
| `JWT_SECRET` | dev, prod | HMAC-ключ для подписи JWT, ≥ 32 байт |
| `DB_URL` | prod | JDBC-URL БД |
| `DB_USERNAME` | prod; в dev опциональна (по умолчанию `myuser`) | пользователь БД |

Прочие прикладные свойства: `app.cors.allowed-origins` (список через запятую; dev —
`http://localhost:5173`, переопределяется окружением `APP_CORS_ALLOWED_ORIGINS`, см. §5),
`app.cleanup.refresh-token-cron` (cron суточной очистки refresh-токенов, общая часть
`application.yaml`, по умолчанию `0 0 4 * * *`, см. §5) и параметры антибрутфорса (§5):
`app.security.auth-rate-limit.capacity` (10) с `refill-duration` (1m) и
`app.security.account-lockout.max-failed-attempts` (5) с `lock-duration` (15m).

Для локальной разработки секреты кладутся в `server/kcr-backend/.env` (копия шаблона
`.env.example`; сам `.env` в `.gitignore`). Профиль `dev` подключает его через
`spring.config.import=optional:file:.env[.properties]` — это properties-файл вида
`KEY=VALUE`, читаемый относительно рабочей директории (`bootRun` и тесты запускаются
из каталога модуля). Переменные окружения приоритетнее значений из `.env`.

Схема БД управляется Flyway: SQL-миграции в `src/main/resources/db/migration` (`V1__init.sql`,
`V2__refresh_token_reuse_detection.sql`, далее `V3__…`) применяются при старте приложения до
инициализации JPA; история — в таблице `flyway_schema_history`. В Boot 4 автоконфигурация Flyway
вынесена в отдельный модуль `spring-boot-flyway` (зависимость вместо голого `flyway-core`,
выявлено 2026-10-26: без него миграции молча не применяются и `ddl-auto=validate` падает по
«missing table»). Каталог миграций — дефолтный (`classpath:db/migration`), явно не задаётся.

## 8. Сборка и запуск

```bash
# из каталога server/kcr-backend
./gradlew build          # сборка + тесты
./gradlew test           # только тесты
./gradlew bootRun        # запуск приложения (нужны PostgreSQL на localhost:5432 и .env, см. §7)
```

## 9. Текущее состояние (актуально на 2026-10-30)

Готово:
- каркас приложения, SecurityConfig (stateless JWT, `/api/auth/**` публичные), JWT-инфраструктура
  (`JwtService`, фильтр, UserDetailsService);
- сущности `User`, `RefreshToken`, `Role`; репозитории;
- полный цикл аутентификации: `AuthController` (`POST /api/auth/register|login|refresh`),
  `AuthService`, `RefreshTokenService` (создание/поиск/ротация с проверкой срока);
- иерархия исключений + `GlobalExceptionHandler` (доменные статусы, валидация, 409 на гонку уникальности);
- тесты: `AuthServiceTest` (9, Mockito), `RefreshTokenServiceTest` (5, Mockito), `JwtServiceTest` (3),
  `AuthFlowIntegrationTest` (e2e-сценарий register → login → refresh + reuse/отзыв семейства,
  требует PostgreSQL), `KcrBackendApplicationTests` (contextLoads, требует PostgreSQL);
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

Выявленные проблемы и требуемые изменения собраны в §10 (архитектурные ревью 2026-10-24
и 2026-10-29); ранее перечислявшиеся здесь «известные особенности» включены туда.

---

## 10. Требуемые изменения (архитектурное ревью 2026-10-24)

Раздел фиксирует проблемы, выявленные ревью от 2026-10-24 (пп. 14–26) и повторным ревью
2026-10-29 по фактическому коду после закрытия пп. 1–13 и 17 (пп. 27–37), и целевые решения.
Пункты пронумерованы сквозной нумерацией, порядок внутри группы — порядок внедрения. Закрывая пункт,
обнови затронутые разделы (§1–§9) в том же изменении и удали закрытый пункт отсюда;
нумерация оставшихся пунктов не сдвигается — номера используются как ссылки из других разделов.

### P1 — до первого продакшена

15. **`DelegatingPasswordEncoder`** вместо голого `BCryptPasswordEncoder` (`{bcrypt}`-префикс
    в хеше) — задел на будущую миграцию на Argon2id без перекладки данных.
16. **Нормализация email до валидации.** `@Email` отклоняет адрес с краевыми пробелами, хотя
    сервис их тримит; тримить до валидации (собственный constraint-валидатор либо нормализация
    на входе контроллера).
27. **Bootstrap первого администратора.** Регистрация всегда создаёт `OPERATOR`, а создание и
    правка клиентов — `hasRole('ADMIN')`; механизма завести первого ADMIN нет — после чистого
    деплоя административный API мёртв, пока роль не проставят руками в БД. Добавить
    идемпотентный `ApplicationRunner`: если пользователей с ролью ADMIN нет — создать учётную
    запись из `ADMIN_USERNAME`/`ADMIN_PASSWORD` (prod — только из окружения, fail-fast; пароль —
    через тот же `PasswordEncoder`); для эксплуатации дополнительно подходит `CommandLineRunner`
    -команда `--create-admin`.
28. **Валидация параметров пагинации.** `PageRequest.of` бросает `IllegalArgumentException`
    при `page < 0` или `size < 1` — `?page=-1` сейчас отдаёт 500; `size` не ограничен сверху —
    `?size=100000` тянет всю таблицу (вектор исчерпания памяти). Ограничить параметры
    constraint-аннотациями (`@Min(0) page`, `@Min(1) @Max(100) size`; в Boot 4 method-validation
    параметров контроллера встроено — нарушение → 400) и завести константу максимального
    размера страницы в общем месте — она же понадобится эндпоинтам истории §11.4.
29. **Один источник правил валидации домена.** Одни и те же инварианты описаны дважды и
    расходятся: ИНН в `ClientCreateRequest`/`ClientUpdateRequest` — ровно 12 цифр, парсер
    импорта — 10 или 12 (клиента-юрлицо с 10-значным ИНН нельзя создать руками, но можно
    импортировать); формат кода KCR (`^[A-Za-z]{3}\d{5}$`, верхний регистр) есть только в
    парсере — ручной `POST /clients` принимает произвольную строку, и будущий upsert по коду
    не сопоставит рукотворный `abc12345` с импортированным `ABC12345`. Вынести правила в
    переиспользуемые Bean Validation-констрейнты (`@Inn`, `@Kpp`, `@KcrCode`: проверка +
    нормализация регистра) и применять их и в DTO, и в парсере — источник данных заменяем
    (§11.6), правила домена — одни.
30. **Регистронезависимая уникальность username и email.** Сравнение сейчас точное: `Admin` и
    `admin` — два разных аккаунта, вход чувствителен к регистру. Перевести колонки на `citext`
    (миграция: `CREATE EXTENSION IF NOT EXISTS citext` + `ALTER … TYPE citext`, уникальные
    констрейнты пересоздаются по citext-семантике) либо функциональные индексы
    `unique (lower(username))` / `unique (lower(email))` с нормализацией к нижнему регистру
    при записи и поиске. Для `clients.kcr_code` citext не нужен: код — идентификатор с
    каноническим видом, достаточно нормализации регистра при записи (п. 29) и обычного unique.
31. **Атомарный инкремент счётчика блокировки.** `AccountLockoutService.registerFailedLogin` —
    read-modify-write над сущностью: две параллельные неудачные попытки читают одно и то же
    значение и обе пишут `+1` — один инкремент теряется, порог срабатывает позже
    `max-failed-attempts`. Заменить на bulk-UPDATE (`@Modifying @Query`):
    `failedAttempts = failedAttempts + 1, lockedUntil = case when failedAttempts + 1 >= :max
    then :deadline else lockedUntil end where username = :username` — атомарно на уровне
    строки и без загрузки сущности; сброс — симметричный `update … set failedAttempts = 0,
    lockedUntil = null where id = :id and (failedAttempts <> 0 or lockedUntil is not null)`.
    `REQUIRES_NEW` сохраняется.
32. **Единый контракт ошибок за пределами MVC-диспетчера.** (а) Токен с верной подписью
    удалённого пользователя: `loadUserById` бросает исключение внутри `JwtAuthenticationFilter`,
    `@RestControllerAdvice` туда не доходит — сейчас 500 вместо 401; загрузку пользователя
    оборачивать, при неудаче оставлять SecurityContext пустым → 401 от
    `RestAuthenticationEntryPoint`. (б) Неперехваченное исключение контроллера уходит в
    дефолтный `/error` мимо `ErrorResponse` — добавить catch-all
    `@ExceptionHandler(Exception.class)` → 500 с нейтральным сообщением (стек — в лог, не в
    ответ). (в) Токен парсится 2–3 раза за запрос (`extractUserId` в фильтре, затем ещё дважды
    внутри `isTokenValid`), каждый парс — полная проверка HMAC-подписи: парсить один раз и
    валидировать subject/срок из полученных `Claims`; это же преддверие п. 20 (доверие claims
    в hot path).

### P2 — качество и готовность к нагрузке

18. **Тесты.** Интеграционные тесты требуют локальный PostgreSQL — перевести на Testcontainers
    (`@ServiceConnection` + `PostgreSQLContainer`), CI станет герметичным. В
    `AuthFlowIntegrationTest` заменить парсинг JSON регулярками на JsonPath. Добавить слайсы
    `@WebMvcTest` (контроллеры) и `@DataJpaTest` (репозитории), AssertJ для ассертов, JaCoCo-порог
    на изменённый код.
19. **Наблюдаемость.** Логгеров в коде нет: добавить SLF4J в сервисы и `JwtAuthenticationFilter`,
    аудит security-событий (login success/fail с username и IP). Подключить
    `spring-boot-starter-actuator` + Micrometer (экспорт в Prometheus); далее — JSON-логи
    (logstash-logback-encoder) и correlation-id.
20. **`JwtAuthenticationFilter` и hot path.** `loadUserByUsername` выполняется на каждый запрос
    (обращение к БД в самом горячем пути): довериться claims из токена (схема и так stateless)
    либо кэш Caffeine с коротким TTL. В `catch` добавить debug-лог причины невалидного токена
    вместо молчаливого проглатывания.
21. **Сборка/CI/рантайм.** Spotless (palantir-java-format) + Error Prone (+ NullAway);
    GitHub Actions на PR (build + тесты); Dependabot/Renovate для зависимостей; переход на
    Java 21 LTS и `spring.threads.virtual.enabled=true` (виртуальные потоки для IO-bound
    нагрузки).
33. **Bulk-удаления и батчинг вставок.** Производные `deleteBy…` Spring Data выполняют SELECT
    и построчные DELETE: logout и reuse-detection отзывают все токены пользователя по одному,
    ночная чистка сначала грузит в память все истёкшие строки. Перевести на
    `@Modifying @Query("delete from RefreshToken t where …")` (+ `clearAutomatically = true`),
    чистку — порциями (цикл с LIMIT), не одной длинной транзакцией; заодно удалить неиспользуемый
    `RefreshTokenRepository.deleteByTokenHash`. Для предстоящего upsert-импорта (§11.8, ~6k
    клиентов и ~12k срезов за файл) заранее включить батчинг: `hibernate.jdbc.batch_size` +
    `order_inserts`/`order_updates` + `reWriteBatchedInserts` драйвера Postgres; сам upsert —
    `INSERT … ON CONFLICT (kcr_code) DO UPDATE` (native) либо пакетный `JdbcTemplate.batchUpdate`.
34. **Индексы под поиск клиентов.** Поиск `lower(kcr_code) LIKE '%q%' OR lower(inn) LIKE '%q%'`:
    ведущий `%` и `lower()` не используют индексы (уникальный индекс `kcr_code` неприменим, по
    `inn` индекса нет) — при росте таблицы это первый seq scan в API. Код KCR и ИНН —
    идентификаторы: искать по префиксу/точно с функциональными индексами
    `(lower(kcr_code)) varchar_pattern_ops` и `(inn) varchar_pattern_ops`; для поиска по
    подстроке — расширение `pg_trgm` + GIN-индекс `gin_trgm_ops`. Одновременно read-методы
    сервисов пометить `@Transactional(readOnly = true)`.
35. **Антибрутфорс: покрытие и доверие X-Forwarded-For.** Бакет на пару username+IP не
    сдерживает массовую регистрацию с одного IP (каждый новый username — новый бакет), а при
    прямом доступе без доверенного прокси XFF подделывается и IP-часть ключа фиктивна
    (комментарий в `AuthController` это признаёт). Добавить второй бакет IP-only на `register`
    (ключ — только IP, тот же Bucket4j), закрыть им же `/refresh` (перебор UUID нереален, но
    запрос дешёвый и безлимитный), доверие к XFF — явным конфигурационным флагом (по умолчанию
    выключено, свойство вида `app.security.trust-forwarded-for`); параметры — в конфигурацию по
    образцу п. 25. При появлении второго инстанса — общий store (Redis), как отмечено в §5.
36. **Отзыв семейства refresh-токенов, а не всех токенов пользователя.** Повторное
    предъявление одного украденного ротированного токена сейчас отзывает все refresh-токены
    пользователя — страдают все устройства, хотя скомпрометировано одно семейство выдачи.
    Завести колонку `family_id` (корень — выдача в login; токены ротации наследуют семейство)
    с индексом; при reuse — `deleteByFamilyId` вместо `deleteByUser`. Атомарность не меняется:
    FOR UPDATE по-прежнему на строке предъявленного токена, удаление семейства — индексное.

### P3 — эволюция

22. **OpenAPI-контракт.** springdoc-openapi для описания `/api/v1/**` и генерации TS-клиента
    для `client/` (префикс `/api/v1/**` введён 2026-10-27 вместе с первым предметным эндпоинтом).
23. **oauth2-resource-server вместо рукописного фильтра.**
    `spring-boot-starter-oauth2-resource-server` даёт валидацию подписи, `iss`/`aud`/`exp` и
    интеграцию со Spring Security из коробки; jjwt остаётся для выдачи токенов. При появлении
    второго сервиса — асимметричная подпись (RS256/ES256) + JWKS-эндпоинт (ротация ключей без
    даунтайма; HMAC-ключ сейчас не ротируется).
24. **Сущности.** `Long id` вместо примитива `long` (новые сущности заводятся сразу на `Long`,
    например `Client`; остались `User` и `RefreshToken`); `equals`/`hashCode` по id; аудит-поля
    (`@EnableJpaAuditing`, `@CreatedDate`/`@LastModifiedDate`); `@Version` при первом сценарии
    конкурентных правок.
25. **Конфигурация.** `@ConfigurationProperties`-record (`JwtProperties`) вместо `@Value`-полей
    в `JwtService`/`RefreshTokenService`.
26. **Маппинг и структура пакетов.** MapStruct (compile-time, без рефлексии) для entity ↔ DTO,
    когда маппинг станет объёмным; при появлении второго домена — переход на package-by-feature
    (`auth/`, `persons/`, `common/`). Текущая слоистая структура для одного домена корректна.
37. **Эксплуатационный минимум рантайма.** Явная настройка HikariCP под инстанс Postgres
    (`maximum-pool-size`, `connection-timeout`, `leak-detection-threshold`), graceful shutdown
    (`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`) — деплой без
    обрыва обрабатываемых запросов, cron джобов — в явной зоне (`@Scheduled(zone = "UTC")`;
    сейчас зона сервера). Дополняет п. 19 (actuator/метрики).

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

### 11.2. Бизнес-правила

- **Отчётные периоды и срезы.** Период — квартал; срез фиксируется после первых 10 рабочих дней
  следующего за кварталом месяца, далее динамика анализируется раз в 1–4 недели (после
  автоматизации — раз в неделю). По одному периоду — несколько срезов. На один сертификат —
  2 документа (заявление и лист ознакомления), каждый считается отдельно: `total_documents` —
  число документов, а не сертификатов.
- **Порог 5%.** Клиент не включается в список на обзвон при доле непереданных < 5%, кроме
  «новичков». Это правило формирования пула, а не запрет на звонок: итоговое решение —
  за администратором.
- **Категории динамики** — вычисляются при импорте среза (относительно предыдущего среза того же
  периода и истории периодов) и сохраняются в срезе; текущая категория клиента — категория
  последнего среза:

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

Доработка `clients` (миграция `V5__client_work_fields.sql`):

| Поле | Тип | Назначение |
|---|---|---|
| stage | enum `WorkStage`, not null (default `NOT_IN_WORK`) | этап работы — отдельно от состояния документов |
| assignee_id | FK `users`, nullable | ответственный оператор; null — не взят в работу |
| next_contact_at | DATE, nullable | следующий контакт (обновляется событием звонка) |
| created_at | TIMESTAMP, not null | появление в сервисе — база правила «новичок < 6 месяцев» |

Доработка `document_transfer_snapshots` (`V6__snapshot_dynamics.sql`):

| Поле | Тип | Назначение |
|---|---|---|
| dynamics_category | enum `DynamicsCategory`, nullable | категория динамики, вычисляется при импорте |
| previous_not_transferred | INT, nullable | значение предыдущего среза («изменение») — денормализация под списки |

Новая сущность `client_events` (`V7__client_event.sql`) — единая история клиента (сценарии 6,
9, 10, 12 ТЗ). Одна широкая таблица с типом и nullable-полями; JOIN-наследование подтипов —
при необходимости позже:

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

Новые сущности `claims` (`V8__claim.sql`) и `sanctions`: претензия — client FK, номер, дата,
номер связанного инцидента, дата получения клиентом, статус `ClaimStatus`, комментарий; санкция —
claim FK, название, статус применения `SanctionStatus`. Мутации претензий и санкций дублируются
событиями `CLAIM`/`SANCTION` в общей истории клиента.

`letter_templates` в MVP не включается (§11.1); при появлении — тривиальная таблица, модель
не меняет.

Enum'ы — все `@Enumerated(STRING)`, varchar + CHECK по списку значений (как `ClientType`):
`WorkStage` (`NOT_IN_WORK → IN_POOL → FIRST_CALL → WAITING_CLIENT_DEADLINE → RECHECK →
SECOND_CALL → CLAIM_WORK → COMPLETED`; `IN_POOL` — «администратор отобрал в общий пул, оператор
ещё не взял»; набор предварительный — вопрос 1 §11.7), `DynamicsCategory` (§11.2),
`ClientEventType`, `CallResult`, `ClaimStatus`, `SanctionStatus` (справочники — §11.7).

### 11.4. План REST API

Префикс `/api/v1/**` (введён 2026-10-27 до первого предметного эндпоинта; `/api/auth` перенесён
в `/api/v1/auth`).

Клиенты и работа с ними:

| Метод и путь | Роль | Назначение |
|---|---|---|
| `GET /api/v1/clients` (расширение фильтров) | обе | фильтры ТЗ поверх базиса (поиск по коду KCR/ИНН, `clientType`, `kcrActive`, `kcrBlocked`, `flag` реализованы): состояние передачи, количество/доля непереданных, этап, наличие претензии, ответственный, отчётный период, новое/повторное появление — по мере появления полей V5/V6/V8 |
| `GET /api/v1/clients/{id}/events` | обе | история, новые → старые, пагинация |
| `POST /api/v1/clients/{id}/events` | обе | создать событие (звонок/комментарий/письмо); `CALL` обновляет `next_contact_at` и, при указании, stage |
| `POST /api/v1/clients/{id}/take` | оператор | атомарно взять свободного клиента |
| `PATCH /api/v1/clients/{id}/assignment` | админ | переназначить/освободить ответственного |
| `PATCH /api/v1/clients/{id}/stage` | админ; оператор — по правилам этапов | смена этапа; создаёт событие |
| `GET /api/v1/clients/{id}/brief` | обе | краткая сводка перед звонком |

Рабочие списки и статистика:

| Метод и путь | Назначение |
|---|---|
| `GET /api/v1/worklist/available` | свободные клиенты из пула — для операторов |
| `GET /api/v1/worklist/today` | «сегодня нужно»: `next_contact_at <= сегодня`; оператору — свои, админу — все |
| `POST /api/v1/worklist` | админ: перевести отобранных клиентов в пул (`IN_POOL`) — после фильтров или ИИ-выборки |
| `GET /api/v1/stats/summary` | счётчики по категориям динамики |

Импорт (Excel, multipart, Apache POI; парсеры-адаптеры готовы — §9, осталось upsert + отчёты
+ события `IMPORT`):

| Метод и путь | Назначение |
|---|---|
| `POST /api/v1/import/clients/preview` | валидация без записи: обязательные колонки, форматы, дубли кода KCR в файле → ошибка с указанием причины, diff новых/обновляемых |
| `POST /api/v1/import/clients` | применение; отчёт: создано / обновлено / строки с ошибками |
| `POST /api/v1/import/snapshots/preview` | валидация: сопоставление по коду KCR, дубли, согласованность счётчиков, наследование `total_documents` |
| `POST /api/v1/import/snapshots` | применение; несопоставленные коды — в отчёте и файлом на скачивание; после записи — пересчёт категорий; загрузка создаёт события `IMPORT` |

Претензии (админ): `POST /api/v1/clients/{id}/claims`, `PATCH /api/v1/claims/{id}`,
`POST /api/v1/claims/{id}/sanctions`, `PATCH /api/v1/sanctions/{id}` — мутации дублируются
в историю клиента.

ИИ-помощник (read-only): `POST /api/v1/ai/questions` — вопрос на естественном языке, ответ строго
по данным сервиса; `POST /api/v1/ai/prioritize` — приоритетная выборка по критериям, только
предложение без мутаций. Изменяющих эндпоинтов у ИИ нет архитектурно; сводка перед звонком —
`GET /api/v1/clients/{id}/brief`.

### 11.5. Целевая структура пакетов

По п. 26 §10 при появлении второго домена — переход на package-by-feature (импорт, события,
претензии, ИИ — отдельные домены). Новые домены заводятся сразу по feature-принципу, перенос
`auth` — отдельным рефакторингом. Правила слоёв внутри пакетов не меняются (§3):

```
ru.quard0r.kcrbackend
├── config/                  — SecurityConfig (CORS — п. 11 §10), бины
├── security/                — JWT-инфраструктура (как сейчас)
├── auth/                    — register/login/refresh (перенос из controller/service)
├── clients/                 — карточки, фильтры, take/assignment/stage, brief
│   ├── ClientController, ClientService
│   ├── model/ (Client, WorkStage, DynamicsCategory, …), repository/, dto/
│   └── events/              — ClientEvent, Claim, Sanction + сервисы
├── snapshots/               — срезы: модель, выборки динамики, категоризация
├── imports/                 — Excel-импорт: парсеры (POI) готовы (§3/§9); добавить UpsertService (§11.6)
├── ai/                      — read-only фасад ИИ-помощника + интеграция с LLM
└── common/                  — исключения, GlobalExceptionHandler, пагинация
```

### 11.6. Заменяемость Excel-импорта на API

Требование ТЗ: позднее Excel-импорт заменяется API-интеграцией без изменения модели данных
и пользовательских сценариев. Следствия:

- парсинг Excel — тонкий адаптер, выдающий те же командные DTO, что выдаст будущий API-клиент:
  `ClientUpsertCommand`, `SnapshotCreateCommand`;
- upsert, категоризация, создание событий живут в сервисном слое и не знают об источнике данных;
- маппинг колонок шаблона Excel — конфигурация адаптера, а не модели.

### 11.7. Открытые вопросы к заказчику

1. Точный список этапов работы и допустимые переходы — набор `WorkStage` в §11.3 предварительный.
2. «Новичок»: подтверждение расчёта по дате появления в сервисе (6 месяцев) и его сверка с типом
   `NEWCOMER` из выгрузки.
3. Справочники: результаты звонка (`CallResult`), статусы претензии (`ClaimStatus`), доступные
   санкции и статусы их применения (`SanctionStatus`).
4. Формат Excel-шаблонов импорта клиентов и срезов, включая исторические данные (заказчик
   приводит их к единому формату). Частично закрыт 2026-10-27 парсерами по реальным выгрузкам
   Дубны (§9): заголовок в строке 2; у клиентов коды лежат в колонке A без заголовка при пустой
   колонке «Код КЦР», карточные колонки (Название/ИНН/КПП/менеджеры) не заполнены; у срезов
   период = имя листа, дата среза — только в имени файла. Открыто: единый шаблон, источник ИНН
   и названия организации (в текущей выгрузке их нет, а `clients.inn` NOT NULL — для upsert
   потребуется решение: договорённость о заполнении шаблона или смягчение ограничения),
   исторические данные.
5. ИИ-помощник: где живёт LLM (прокси-эндпоинт бекенда с ключом из окружения или отдельный сервис
   в `ml/` монорепо), модель и бюджет; состав контрольных сценариев приёмки.
6. (решение по проекту, не заказчику) Переход на package-by-feature (§11.5).

### 11.8. Порядок внедрения

0. **Предпосылки из §10**, критичные для ТЗ-функционала: осталась часть п. 24 — аудит-поля
   (`@EnableJpaAuditing`, `@CreatedDate`: `clients.created_at` — база правила «новичок»; входит
   в `V5` следующего шага). Пп. 7, 11 и префикс п. 22 закрыты 2026-10-27 вместе с CRUD клиентов.
1. **Операторский цикл**: `V5` (поля процесса) + `V7` (`client_events`); создание и выдача
   событий (звонок обновляет `next_contact_at`/stage), `take`/`assignment`/`stage` с
   атомарностью взятия, `GET /worklist/today`.
2. **Динамика** (`V6`): сервис категоризации срезов, `previous_not_transferred`, фильтры
   по динамике в списке.
3. **Excel-импорт**: парсеры-адаптеры клиентов и срезов готовы (2026-10-27, пакет `imports`,
   §9). Осталось: upsert-сервис (upsert по коду KCR без удаления отсутствующих, наследование
   `total_documents`, пересчёт категорий, события `IMPORT`) и эндпоинты preview/применение
   с отчётами (§11.4).
4. **Пул и статистика**: `POST /worklist` (перевод в `IN_POOL`), `GET /worklist/available`,
   `GET /stats/summary`.
5. **Претензии и санкции** (`V8`) + API.
6. **ИИ-помощник**: read-only фасад (`brief`, `questions`, `prioritize`), интеграция LLM,
   контрольные сценарии.

Тесты — по ходу каждого пункта (юнит сервисов, `@DataJpaTest` репозиториев, `@WebMvcTest`
контроллеров, интеграционные сценарии; Testcontainers — п. 18 §10). Каждый пункт — отдельная
ветка/PR в `develop` (конвенция `feature/auth`); закрываемые пункты §11 переносятся в §3/§4/§9
в том же PR.
