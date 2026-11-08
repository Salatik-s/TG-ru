FROM node:24-bookworm-slim AS xray-download
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip && rm -rf /var/lib/apt/lists/*
RUN curl --proto '=https' --tlsv1.2 -fsSL https://github.com/XTLS/Xray-core/releases/download/v26.10.31/Xray-linux-64.zip -o /tmp/xray.zip \
    && echo 'e4bd9119f50ae7c41658b1abd0679ae06f98bd864a2907ff33f2f2cf7081b214  /tmp/xray.zip' | sha256sum -c - \
    && unzip /tmp/xray.zip xray -d /tmp/xray && chmod 755 /tmp/xray/xray
FROM node:24-bookworm-slim
WORKDIR /app
COPY --from=xray-download /tmp/xray/xray /usr/local/bin/xray
COPY package.json *.mjs ./
USER 10001:10001
EXPOSE 8080 18443
CMD ["node", "main.mjs"]
