# ARCHITECTURE — kcr-backend

Архитектурная документация kcr-backend: назначение, стек, структура пакетов, доменная модель,
аутентификация и текущее состояние. Любое изменение структуры пакетов, стека или доменной модели
**должно** отражаться в этом файле.

---

## 1. Назначение проекта

**kcr-originals-tracker** — монорепозиторий системы учёта оригиналов документов (KCR Originals Tracker).

`server/kcr-backend` — REST-бэкенд на Spring Boot. Отвечает за:

- аутентификацию и авторизацию (JWT: access- + refresh-токены);
- хранение пользователей и их ролей;
- (в перспективе) учёт оригиналов документов — сущность `Person` и далее предметные сущности.

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
| Тесты | JUnit 5 (spring-boot-starter-*-test) | из BOM Boot |

Группа: `ru.quard0r`, корневой пакет: `ru.quard0r.kcrbackend`.

## 3. Структура пакетов (слоистая архитектура)

```
ru.quard0r.kcrbackend
├── KcrBackendApplication   — точка входа (@SpringBootApplication)
├── config/                 — конфигурация Spring (SecurityConfig, бины)
├── controller/             — REST-контроллеры (AuthController: /api/auth/**)
├── dto/
│   ├── request/            — входные DTO (records: LoginRequest, RegisterRequest, RefreshRequest)
│   └── response/           — исходящие DTO (records + @Builder: AuthResponse, ErrorResponse)
├── exception/
│   ├── base/               — иерархия базовых исключений (см. §6)
│   └── <Конкретное>Exception — предметные исключения
├── model/                  — JPA-сущности (User, RefreshToken, Role, Person) + enum'ы
├── repository/             — Spring Data JPA репозитории (UserRepository, RefreshTokenRepository)
├── security/               — JWT-инфраструктура (JwtService, JwtAuthenticationFilter,
│                             CustomUserDetailsService)
└── service/                — бизнес-логика (AuthService, RefreshTokenService)
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

### Person
Пустая заготовка под будущую предметную сущность.

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
- Собственный `AuthenticationEntryPoint` не настроен: на защищённые маршруты без валидного токена
  Spring Security (без единого настроенного auth-механизма) отвечает дефолтным **403** с телом
  не по контракту `ErrorResponse` (единый контракт — §10, п. 7).
- Публичные маршруты: `/api/auth/**`; всё остальное — только аутентифицированным.
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
        │   ├── InvalidCredentialsException       → 401 UNAUTHORIZED
        │   └── UserNotFoundException             → 404 NOT_FOUND
        └── ServerException              (base/, 500 INTERNAL_SERVER_ERROR)
```

`GlobalExceptionHandler` (`@RestControllerAdvice`) перехватывает `HttpException` и возвращает
`ErrorResponse(message, timestamp)` с соответствующим HTTP-статусом. Сервисы бросают только
доменные исключения; маппинг в HTTP — исключительная зона ответственности хендлера. Дополнительно
хендлер покрывает: `MethodArgumentNotValidException` (400, сводка ошибок валидации DTO),
`HttpMessageNotReadableException` (400, невалидный JSON) и `DataIntegrityViolationException`
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

Выявленные проблемы и требуемые изменения собраны в §10 (архитектурное ревью от 2026-10-24);
ранее перечислявшиеся здесь «известные особенности» включены туда.

---

## 10. Требуемые изменения (архитектурное ревью 2026-10-24)

Раздел фиксирует проблемы, выявленные ревью от 2026-10-24, и целевые решения. Пункты
пронумерованы сквозной нумерацией, порядок внутри группы — порядок внедрения. Закрывая пункт,
обнови затронутые разделы (§1–§9) в том же изменении и удали закрытый пункт отсюда;
нумерация оставшихся пунктов не сдвигается — номера используются как ссылки из других разделов.

### P1 — до первого продакшена

7. **Единый контракт ошибок 401.** Кастомного `AuthenticationEntryPoint` нет (см. §5): без
   настроенного auth-механизма Spring Security 7 отвечает дефолтным **403** с телом не по
   контракту `ErrorResponse(message, timestamp)` — фронтенд получает разные статусы и форматы
   ошибок. Добавить entry point, возвращающий `ErrorResponse` (или перевести ошибки API на
   ProblemDetail целиком).
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
10. **Logout-эндпоинт** (`POST /api/auth/logout`): удалять refresh-токены текущего пользователя
    (`deleteByToken`/`deleteByUser` в `RefreshTokenRepository` уже объявлены, но не задействованы).
11. **CORS.** `CorsConfigurationSource` + `.cors(...)` в `SecurityConfig` под origin клиента
    (`client/`) — SPA с другого порта/домена без этого не заработает.
12. **Антибрутфорс.** Rate limiting на `/api/auth/login|register` (Bucket4j, лимит на пару
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

22. **Версионирование API + OpenAPI.** Префикс `/api/v1/**` (вводить сразу — потом поздно);
    springdoc-openapi для контракта и генерации TS-клиента для `client/`.
23. **oauth2-resource-server вместо рукописного фильтра.**
    `spring-boot-starter-oauth2-resource-server` даёт валидацию подписи, `iss`/`aud`/`exp` и
    интеграцию со Spring Security из коробки; jjwt остаётся для выдачи токенов. При появлении
    второго сервиса — асимметричная подпись (RS256/ES256) + JWKS-эндпоинт (ротация ключей без
    даунтайма; HMAC-ключ сейчас не ротируется).
24. **Сущности.** `Long id` вместо примитива `long`; `equals`/`hashCode` по id; аудит-поля
    (`@EnableJpaAuditing`, `@CreatedDate`/`@LastModifiedDate`); `@Version` при первом сценарии
    конкурентных правок; удалить пустую заготовку `Person` (YAGNI — вернуть по необходимости).
25. **Конфигурация.** `@ConfigurationProperties`-record (`JwtProperties`) вместо `@Value`-полей
    в `JwtService`/`RefreshTokenService`.
26. **Маппинг и структура пакетов.** MapStruct (compile-time, без рефлексии) для entity ↔ DTO,
    когда маппинг станет объёмным; при появлении второго домена — переход на package-by-feature
    (`auth/`, `persons/`, `common/`). Текущая слоистая структура для одного домена корректна.
