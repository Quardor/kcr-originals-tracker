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

# Общий секрет для backend и AI-сервиса (задайте длинное случайное значение)
JWT_SECRET=change-me-32-bytes-minimum

# Учётная запись администратора при первом запуске пустой БД
ADMIN_USERNAME=admin
ADMIN_PASSWORD=change-me

# Подключение к LLM (OpenAI-compatible API)
LLM_PROVIDER=api
LLM_BASE_URL=https://api.example.com/v1
LLM_API_KEY=your-llm-api-key
LLM_MODEL=your-model-name
```

Вместо `https://api.example.com/v1` и `your-model-name` укажите URL и название модели вашего LLM-провайдера. Не оставляйте демонстрационные пароли на публичном или рабочем стенде.

### 3. Собрать и запустить проект

Из корневой папки репозитория выполните:

```bash
docker compose up --build -d
```

Команда соберёт и запустит PostgreSQL, backend, AI-сервис и frontend.

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

Данные PostgreSQL сохраняются в Docker volume и останутся после остановки контейнеров. Чтобы удалить контейнеры **вместе со всеми данными БД**, используйте:

```bash
docker compose down -v
```

> Не добавляйте `.env` в Git: в нём хранятся пароли и API-ключ LLM.
