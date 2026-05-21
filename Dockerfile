FROM node:22-bookworm-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    && rm -rf /var/lib/apt/lists/*

COPY package*.json ./

RUN rm -f package-lock.json && npm install --omit=dev

COPY . .

RUN groupadd -r appgroup && useradd -r -g appgroup appuser && chown -R appuser:appgroup /app

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD wget -qO- http://localhost:8000/ || exit 1

CMD ["node", "index.js"]
