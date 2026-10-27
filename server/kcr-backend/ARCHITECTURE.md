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
├── exception/
│   ├── base/               — иерархия базовых исключений (см. §6)
│   └── <Конкретное>Exception — предметные исключения
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
├── security/               — JWT-инфраструктура (JwtService, JwtAuthenticationFilter,
│                             CustomUserDetailsService, RestAuthenticationEntryPoint)
└── service/                — бизнес-логика (AuthService, RefreshTokenService, ClientService)
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
наследования total (индекс на FK создаётся явно — Postgres не строит их автоматически, см. п. 9 §10).

## 5. Аутентификация (JWT)

Схема stateless, без сессий (`SessionCreationPolicy.STATELESS`), CSRF отключён.

- Пароли хранятся как BCrypt-хеш (`BCryptPasswordEncoder`).
- Access-токен: подпись HMAC-SHA (ключ из `jwt.secret`), subject = username,
  claims: `email`, `roles`; срок — `jwt.access-expiration` (15 мин).
- Refresh-токен: сырой UUID отдаётся клиенту, в БД хранится только его SHA-256-хеш,
  поиск — по хешу; срок — `jwt.refresh-expiration` (7 дней).
- `JwtAuthenticationFilter` (before `UsernamePasswordAuthenticationFilter`) читает заголовок
  `Authorization: Bearer <token>`, валидирует и заполняет `SecurityContext`. Авто-регистрация фильтра
  в servlet-контейнере отключена через `FilterRegistrationBean` (иначе он вызывается вне security-цепочки
  и его SecurityContext затирается).
- `RestAuthenticationEntryPoint` отдаёт единый контракт ошибок неаутентифицированным запросам:
  **401** с телом `ErrorResponse` (JSON) вместо дефолтного 403.
- CORS: бин `CorsConfigurationSource` + `.cors(...)` в цепочке; источники — свойство
  `app.cors.allowed-origins` (список через запятую; в dev — `http://localhost:5173`, переопределяется
  переменной окружения `APP_CORS_ALLOWED_ORIGINS`). Разрешены методы GET/POST/PUT/PATCH/DELETE/OPTIONS
  и заголовки `Authorization`, `Content-Type`; credentials не включены (токен — в заголовке). Пустой
  список (по умолчанию) — кросс-доменные запросы не разрешены, same-origin работает без ограничений.
- Публичные маршруты: `/api/v1/auth/**`; всё остальное — только аутентифицированным.
- Включена method security (`@EnableMethodSecurity`) — можно использовать `@PreAuthorize` по ролям.
- `CustomUserDetailsService` возвращает `CustomUserDetails` — record-обёртку над сущностью `User`
  (роль → authority `ROLE_<ROLE>`); она же — principal в `SecurityContext` после фильтра и в
  результате `authenticate(...)`.

Сценарии (реализованы в `AuthService`; `register`/`login` — `@Transactional`, `refresh` — нет,
см. ниже):
- `register` — проверка уникальности username и email (нормализованного: blank → null) →
  сохранение (BCrypt, роль OPERATOR) → выдача пары токенов. Конкурентный конфликт уникальности в БД
  ловится `GlobalExceptionHandler` как 409.
- `login` — через `authenticationManager.authenticate(...)` (бин в `SecurityConfig`;
  `DaoAuthenticationProvider` собирается из бинов `CustomUserDetailsService` + `PasswordEncoder`):
  любой `AuthenticationException` → единый 401 "Invalid credentials". Тайминг-атака на
  перечисление usernames закрыта самим провайдером: при `UsernameNotFoundException` он выполняет
  холостую bcrypt-сверку (`mitigateAgainstTimingAttack`), время ответа не выдаёт существование
  username. Сущность для выдачи токенов достаётся из principal (`CustomUserDetails.user()`).
- `refresh` — ротация одним методом `RefreshTokenService.rotate(rawToken)` (SELECT по хешу с
  JOIN FETCH пользователя): неизвестный и просроченный токен → 401 (просроченный удаляется),
  повторное предъявление ротированного токена (reuse-detection, паттерн token family) → отзыв
  всех токенов пользователя (`deleteByUser`) и 401; активный токен помечается `rotated_date`
  и заменяется новым. `rotate` возвращает `Optional` и не бросает исключение изнутри транзакции,
  а `AuthService.refresh` намеренно без `@Transactional` — иначе откат транзакции по 401 отменял
  бы уже сделанный отзыв семейства.

## 6. Иерархия исключений

```
RuntimeException
└── BasicException                       (base/,RuntimeException с сообщением)
    └── HttpException (abstract)         (base/, abstract HttpStatus getHttpStatus())
        ├── BadRequestException          (base/, по умолчанию 400)
        │   ├── CredentialsAlreadyExistsException → 409 CONFLICT
        │   ├── ClientAlreadyExistsException      → 409 CONFLICT
        │   ├── InvalidCredentialsException       → 401 UNAUTHORIZED
        │   ├── UserNotFoundException             → 404 NOT_FOUND
        │   └── ClientNotFoundException           → 404 NOT_FOUND
        └── ServerException              (base/, 500 INTERNAL_SERVER_ERROR)
```

`GlobalExceptionHandler` (`@RestControllerAdvice`) перехватывает `HttpException` и возвращает
`ErrorResponse(message, timestamp)` с соответствующим HTTP-статусом. Сервисы бросают только
доменные исключения; маппинг в HTTP — исключительная зона ответственности хендлера. Дополнительно
хендлер покрывает: `MethodArgumentNotValidException` (400, сводка ошибок валидации DTO),
`HttpMessageNotReadableException` (400, невалидный JSON), `MethodArgumentTypeMismatchException`
(400, несовместимое значение query/path-параметра) и `DataIntegrityViolationException`
(409, гонка уникальности username/email при конкурентной регистрации).

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
`http://localhost:5173`, переопределяется окружением `APP_CORS_ALLOWED_ORIGINS`, см. §5).

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

## 9. Текущее состояние (актуально на 2026-10-26)

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

Выявленные проблемы и требуемые изменения собраны в §10 (архитектурное ревью от 2026-10-24);
ранее перечислявшиеся здесь «известные особенности» включены туда.

---

## 10. Требуемые изменения (архитектурное ревью 2026-10-24)

Раздел фиксирует проблемы, выявленные ревью от 2026-10-24, и целевые решения. Пункты
пронумерованы сквозной нумерацией, порядок внутри группы — порядок внедрения. Закрывая пункт,
обнови затронутые разделы (§1–§9) в том же изменении и удали закрытый пункт отсюда;
нумерация оставшихся пунктов не сдвигается — номера используются как ссылки из других разделов.

### P1 — до первого продакшена

8. **Защитить refresh-флоу от гонки.** Ротация — уже один метод `rotate` (SELECT по хешу с
   JOIN FETCH, см. §5), но без блокировки: при двух конкурентных `/refresh` с одним токеном
   либо выдаются оба новых токена, либо второй запрос выглядит как reuse и отзывает семейство
   вместе со свежим токеном первого. Добавить `@Lock(LockModeType.PESSIMISTIC_WRITE)`
   (SELECT … FOR UPDATE) на чтение токена в одной транзакции с пометкой ротации и вставкой
   нового токена.
9. **Очистка просроченных токенов + индекс.** `@EnableScheduling` + `@Scheduled`-job
   (`deleteByExpiryDateBefore(now)`, раз в сутки) — сейчас таблица растёт бесконечно. Индекс на
   `refresh_tokens.user_id` (`@Table(indexes = @Index(...))` + миграция): Postgres не создаёт
   индексы на FK автоматически, `deleteByUser` без индекса уходит в seq scan.
10. **Logout-эндпоинт** (`POST /api/v1/auth/logout`): удалять refresh-токены текущего пользователя
    (`deleteByToken`/`deleteByUser` в `RefreshTokenRepository` уже объявлены, но не задействованы).
12. **Антибрутфорс.** Rate limiting на `/api/v1/auth/login|register` (Bucket4j, лимит на пару
    username+IP) + блокировка аккаунта после N неудачных попыток (`failed_attempts`/`locked_until`
    на `User`, учитывать при загрузке в `CustomUserDetailsService`).
13. **Claims access-токена.** Subject — `user.getId()` вместо username (username изменяем), плюс
    `preferred_username` и `jti` (`JwtService.generateAccessToken`).
14. **Упростить иерархию исключений и `ErrorResponse`.** `BasicException` — пустой уровень без
    полей; `CredentialsAlreadyExistsException extends BadRequestException` возвращает 409 (ломает
    семантику is-a); отдельный класс на каждый статус избыточен. Заменить на один доменный класс
    исключения + enum `ErrorCode { код, HttpStatus, сообщение }`. В `ErrorResponse`: `timestamp` →
    ISO-8601 (`Instant` вместо epoch-millis), добавить машиночитаемый `code` для фронтенда.
    Хендлер `DataIntegrityViolationException` различать нарушения по имени констрейнта — сейчас
    любое нарушение целостности отвечает «Username or email already taken».
15. **`DelegatingPasswordEncoder`** вместо голого `BCryptPasswordEncoder` (`{bcrypt}`-префикс
    в хеше) — задел на будущую миграцию на Argon2id без перекладки данных.
16. **Нормализация email до валидации.** `@Email` отклоняет адрес с краевыми пробелами, хотя
    сервис их тримит; тримить до валидации (собственный constraint-валидатор либо нормализация
    на входе контроллера).

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
