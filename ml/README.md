# kcr-ml — ИИ-помощник сервиса КЦР

Python/FastAPI-сервис ИИ-помощника поверх данных kcr-backend: чат с данными,
сводки перед звонком, приоритизация клиентов, аналитика для дашбордов и экспорт
в Excel. Read-only по построению: мутирующих путей нет ни на одном слое.
Архитектура и контракты — в [ARCHITECTURE.md](./ARCHITECTURE.md).

## Стек

Python ≥ 3.12, FastAPI + uvicorn, Pydantic v2 (pydantic-settings), httpx,
OpenAI-совместимый LLM-клиент (облачный API и локальный Qwen), PyJWT,
openpyxl/pandas, uv (пакетный менеджер), ruff (линт/формат), pytest.

## Запуск (разработка)

Нужны: [uv](https://docs.astral.sh/uv/), kcr-backend на `KCR_BACKEND_BASE_URL`
(dev: `http://localhost:8080`), общий `jwt.secret` и доступ к LLM-провайдеру.

```bash
cp .env.example .env        # заполнить секреты (AUTH_JWT_SECRET, LLM_*)
uv sync                     # окружение из uv.lock
uv run uvicorn kcr_ml.main:app --reload   # http://localhost:8000
```

Проверки:

```bash
uv run ruff check . && uv run ruff format --check .
uv run pytest
```

Docker: `docker compose up --build` (app на :8000); локальный Qwen —
`docker compose --profile local up` (Ollama на :11434, `LLM_PROVIDER=local`,
`LLM_BASE_URL=http://ollama:11434/v1`, модель через `docker exec … ollama pull qwen…`).

## API

Префикс `/api/v1/ai/**`, аутентификация — Bearer access-токен kcr-backend
(проверяется HS256 общим секретом и пробрасывается в бэкенд как есть).
Публичный — только `GET /health`. Полный контракт — §7 ARCHITECTURE.md;

| Эндпоинт | Назначение |
|---|---|
| `GET /health` | liveness |
| `WS /api/v1/ai/chat?token=…` | UC-1 чат (стриминг + tool calling + таблицы) |
| `GET /api/v1/ai/clients/{id}/brief` | UC-2 сводка перед звонком |
| `POST /api/v1/ai/prioritize` | UC-3 приоритетная выборка (админ) |
| `GET /api/v1/ai/analytics/{summary,dynamics,top-debt,operators}` | UC-4 агрегаты |
| `POST /api/v1/ai/export/xlsx` | UC-5 экспорт в xlsx (админ) |

## Состояние внедрения (§15 ARCHITECTURE.md)

Все пункты 1–8 реализованы (2026-11-08, ветка `feature/ai-agent`):

1. Каркас: uv-проект, ruff, config, `/health`, Dockerfile + compose — готово.
2. Аутентификация (JWT kcr-backend) + `DataSourcePort` на httpx — готово.
3. Аналитика (UC-4, без LLM) — готово. Категории динамики считаются в kcr-ml
   детерминированно (`domain/dynamics.py`), пока бэкенд не реализует V11.
4. Prioritize (UC-3): `LlmPort` + OpenAI-совместимый адаптер, скоринг в domain — готово.
5. Brief (UC-2) — готово.
6. Чат (UC-1): WebSocket, tool-цикл, стриминг — готово.
7. Excel-экспорт (UC-5): openpyxl/pandas — готово.
8. Локальный Qwen — конфигурация (`LLM_PROVIDER=local` + compose profile `local`);
   контрольные сценарии §14 — тесты в `tests/scenarios/` на детерминированной
   заглушке LLM (70 тестов всего). Прогон живой моделью — вручную по тем же
   сценариям после выбора модели (открытый вопрос §16.1).

## Каталоги

- `src/kcr_ml/domain/` — чистый слой: read-model, категории динамики, критерии
  и скоринг, промпты;
- `src/kcr_ml/application/` — use-case'ы и порты (`ports.py`);
- `src/kcr_ml/infrastructure/` — адаптеры: LLM (OpenAI-совместимый), данные
  (httpx, только GET), Excel, JWT;
- `src/kcr_ml/api/` — REST-роутеры, WebSocket, Pydantic-схемы;
- `tests/` — unit (фейки портов), integration (ASGI), scenarios (приёмка ТЗ).
