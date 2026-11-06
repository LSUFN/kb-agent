# syntax=docker/dockerfile:1
# 前端镜像（M6）：Node 构建静态文件，Nginx 托管并把 /api 反向代理到后端。

# ---------- 构建 ----------
FROM node:22-alpine AS build
WORKDIR /src
# 可选的 npm 镜像，例如 https://registry.npmmirror.com（国内网络下载依赖更快）
ARG NPM_REGISTRY=
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi \
    && npm ci --no-audit --no-fund
COPY . .
# 类型检查 + 打包
RUN npm run build

# ---------- 运行 ----------
FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
