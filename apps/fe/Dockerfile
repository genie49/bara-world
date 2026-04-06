FROM node:22-alpine AS build
RUN corepack enable && corepack prepare pnpm@latest --activate
WORKDIR /app
COPY apps/fe/package.json apps/fe/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY apps/fe/ .
RUN pnpm build

FROM nginx:alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY apps/fe/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 5173
CMD ["nginx", "-g", "daemon off;"]
