FROM node:22-bookworm-slim AS builder

WORKDIR /workspace

RUN corepack enable

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json

RUN pnpm install --frozen-lockfile --filter @lynxus/web...

COPY apps/web apps/web

ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN pnpm --filter @lynxus/web build

FROM nginx:1.27-alpine

COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/apps/web/dist /usr/share/nginx/html

EXPOSE 80
