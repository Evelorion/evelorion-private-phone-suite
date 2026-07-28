FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY package.json ./
RUN npm install --omit=dev && cp -R node_modules /prod_modules && npm install
COPY tsconfig.json ./
COPY src ./src
RUN npx tsc -p tsconfig.json

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /prod_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
# 网页端是纯静态文件，不需要构建
COPY web ./web
COPY scripts ./scripts
COPY src ./src
# 用镜像自带的 node 用户，不要自己 useradd。
# 踩过的坑：`useradd sync` 会失败 —— Debian 自带一个 uid=4 的系统账号就叫 sync。
# 静态资源要让非 root 的 node 用户读得到。
# 踩过的坑：从挂载盘打包过去的文件可能是 600，容器里直接 EACCES。
RUN chmod -R a+rX /app/web /app/scripts /app/src \
 && mkdir -p /app/data && chown -R node:node /app/data
USER node
VOLUME ["/app/data"]
EXPOSE 8443
CMD ["node", "dist/index.js"]
