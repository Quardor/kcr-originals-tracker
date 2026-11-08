# Сборка SPA: зависимости из lockfile; VITE_* запекаются в бандл на этапе сборки.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Относительные base-URL: в контейнерном стенде nginx проксирует /api на сервисы,
# браузер работает с одним origin (см. docker-compose.yml в корне репозитория)
ENV VITE_API_BASE_URL=/api/v1 \
    VITE_AI_BASE_URL=/api/v1/ai
RUN npm run build

# Раздача статики + обратный прокси на app/ml (nginx.conf).
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
