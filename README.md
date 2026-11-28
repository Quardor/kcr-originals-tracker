<p align="center">
  <a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1"><img src="https://i.ibb.co/qLXZk5cj/Vector.png" alt="originals" style="transform: scale(2);"> </a>
</p>

---


## Краткая инструкция по запуску проекта локально через Docker Compose.

### 1. Подготовить окружение

Установите Docker с поддержкой Docker Compose. Склонируйте репозиторий и перейдите в его корень:

```bash
git clone https://github.com/Quardor/kcr-originals-tracker.git
cd kcr-originals-tracker
```

Создайте файл конфигурации:

```bash
cp .env.example .env
```

### 2. Заполнить `.env`

Откройте `.env` и укажите параметры:

```env
# База данных
DB_PASSWORD=change-me

# Секрет для подписи JWT-токенов backend — минимум 32 байта
JWT_SECRET=<случайное значение>

# Внутренний сервисный токен для вызовов backend → AI-сервис — минимум 32 байта
ML_INTERNAL_TOKEN=<случайное значение>

# Учётная запись администратора при первом запуске пустой БД
ADMIN_USERNAME=admin
ADMIN_PASSWORD=change-me

# Подключение к LLM (OpenAI-compatible API)
LLM_PROVIDER=api
LLM_BASE_URL=https://api.example.com/v1
LLM_API_KEY=your-llm-api-key
LLM_MODEL=your-model-name
```

`JWT_SECRET` и `ML_INTERNAL_TOKEN` — два разных значения, каждое длиной не меньше 32 байтов. Значения из `.env.example` (`change-me-32-bytes-minimum`) короче минимума — с ними backend и AI-сервис не стартуют. Сгенерировать каждое:

```bash
openssl rand -hex 32
```

Вместо `https://api.example.com/v1` и `your-model-name` укажите URL и название модели вашего LLM-провайдера. Облачный провайдер получает данные клиентов (названия, ИНН, комментарии); для реальных данных нужен локальный Qwen — `docker compose --profile local up -d ollama` и `LLM_PROVIDER=local` в `.env` (подробности — в [ml/README.md](ml/README.md)).

Не оставляйте демонстрационные пароли на публичном или рабочем стенде.

### 3. Собрать и запустить проект

Из корневой папки репозитория выполните:

```bash
docker compose up --build -d
```

Команда соберёт и запустит PostgreSQL, backend, AI-сервис и frontend.

| Сервис | Адрес | Для чего опубликован |
|---|---|---|
| frontend | http://localhost:5173 | приложение — единственный адрес для браузера |
| backend | http://localhost:8080 | REST API `/api/v1` — для разработки и проверок |
| PostgreSQL | `localhost:5433` (база и пользователь `kcr`) | для инструментов разработки |
| AI-сервис | не публикуется | доступен только backend |

Браузер обращается только к frontend: nginx отдаёт приложение и проксирует `/api/v1/**` в backend, а backend сам вызывает AI-сервис. Образы не следят за кодом: после `git pull` или переключения ветки повторите ту же команду, чтобы их пересобрать.

Проверить состояние контейнеров:

```bash
docker compose ps
```

Посмотреть логи:

```bash
docker compose logs -f
```

### 4. Открыть приложение

Перейдите в браузере по адресу:

**http://localhost:5173**

Для первого входа используйте учётные данные администратора из `.env`:

- **Логин:** `admin`
- **Пароль:** `change-me`

Администратор автоматически создаётся только тогда, когда в базе ещё нет пользователя с ролью `ADMIN`. Изменение `ADMIN_USERNAME` или `ADMIN_PASSWORD` в `.env` не меняет уже созданную учётную запись.

### 5. Остановить проект

```bash
docker compose down
```

Данные PostgreSQL сохраняются в Docker volume и останутся после остановки контейнеров. Чтобы удалить контейнеры **вместе со всеми данными БД** (и скачанными моделями Ollama), используйте:

```bash
docker compose down -v
```

### Если что-то не запускается

Причина видна в логах сервиса — например, `docker compose logs app`:

- **backend** не стартует: `JWT_SECRET` или `ML_INTERNAL_TOKEN` не заданы либо короче 32 байтов; при пустой базе — не заданы `ADMIN_USERNAME`/`ADMIN_PASSWORD`.
- **AI-сервис** не стартует: `ML_INTERNAL_TOKEN` короче 32 байтов; при `LLM_PROVIDER=api` пуст `LLM_API_KEY`.
- **Сборка** оборвалась на скачивании пакетов (`ECONNRESET`): повторите `docker compose up --build -d`; при плохой сети собирайте сервисы по одному — `docker compose build app`, затем `ml`, `frontend`.
- **http://localhost:5173** не открывается: порт занят — dev-сервер Vite (`npm run dev`) и стенд работают только по очереди.

> Не добавляйте `.env` в Git: в нём хранятся пароли и API-ключ LLM.
