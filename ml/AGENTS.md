# AGENTS.md — правила работы агентов в kcr-ml

## 0. Обязательный первый шаг

**Перед выполнением любой задачи прочитай [ARCHITECTURE.md](./ARCHITECTURE.md) целиком.**
Меняешь архитектуру, стек или структуру проекта — обнови соответствующие разделы
ARCHITECTURE.md в том же изменении.

## 1. Стек и команды

Python ≥ 3.12, FastAPI, Pydantic v2. Пакетный менеджер — только **uv**, линтер/формatter —
только **ruff** (конфигурация в `pyproject.toml`).

```bash
uv sync                                # окружение из uv.lock
uv run pytest                          # тесты
uv run ruff check . --fix              # линт
uv run ruff format .                   # форматирование
uv run uvicorn kcr_ml.main:app --reload  # запуск (нужны .env и kcr-backend)
```

## 2. Архитектура (Clean Architecture, §3 ARCHITECTURE.md)

Направление зависимостей строгое: `api → application → domain`; `infrastructure` реализует
порты `application`, домен ни от чего не зависит.

- `domain/` — чистый Python: без FastAPI, httpx, SDK LLM, pandas.
- `application/ports.py` — порты (`LlmPort`, `DataSourcePort`, `ExcelExporterPort`);
  use-case'ы зависят только от портов и domain.
- Новый LLM (облачный API или локальный Qwen) и новый источник данных — новый адаптер
  в `infrastructure/` + выбор в factory; сервисы не правятся. OpenAI-совместимые провайдеры
  подключаются настройками окружения, без кода.
- **Read-only инвариант ТЗ**: `DataSourcePort` и адаптер `infrastructure/data/` — только
  чтение (GET). Мутирующих путей не добавлять никогда.
- **LLM не выдумывает данные**: списки и числа в ответах — только из источника данных;
  LLM структурирует запрос и формулирует текст (схема UC-3 в §2 ARCHITECTURE.md).
- Excel — только в `infrastructure/excel/` (openpyxl/pandas), готовые решения.
- Pydantic-схемы — на всех границах: REST, WebSocket, конфигурация, ответы LLM.

## 3. Оформление

- ruff — единый источник стиля, line-length 120; аннотации типов обязательны.
- `snake_case` — модули/функции/поля, `PascalCase` — классы, `UPPER_SNAKE_CASE` — константы.
- Код и сообщения об ошибках — на английском; документация — на русском.
- Один модуль — одна ответственность; каталоги `utils`/`helpers` не создаются.

## 4. Безопасность

- Секреты (`AUTH_JWT_SECRET`, `LLM_API_KEY`) — только окружение/`.env` (вне git).
- Access-токен kcr-backend проверяется (HS256, общий секрет) и пробрасывается в бэкенд
  как есть; kcr-ml токены не выдаёт, токены и секреты не логирует.
- Новые эндпоинты по умолчанию защищены; публичный — только `GET /health`.

## 5. Проверка перед завершением

1. `uv run ruff check .` и `uv run ruff format --check .` — чисто.
2. `uv run pytest` — успешно; результат сообщай честно, без приукрашивания.
3. Слои и порты не нарушены (§2); изменил архитектуру — обновил ARCHITECTURE.md.
4. В итоговом ответе перечисли изменённые файлы и результат проверок.
