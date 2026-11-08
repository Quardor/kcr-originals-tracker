# Multi-stage build (ARCHITECTURE.md §12): dependencies from uv.lock, slim runtime.

FROM python:3.12-slim AS deps
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

FROM python:3.12-slim
WORKDIR /app
COPY --from=deps /app/.venv /app/.venv
COPY src/ ./src/
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1
RUN useradd --system --uid 1001 kcr && chown -R kcr:kcr /app
USER kcr
EXPOSE 8000
CMD ["uvicorn", "kcr_ml.main:app", "--host", "0.0.0.0", "--port", "8000"]
