# Pacote de execução: a interface já está compilada na pasta dist/.
FROM node:22-alpine
ENV NODE_ENV=production PORT=3000 BEDROCK_DATA_DIR=/data/bedrock PANEL_STATE_DIR=/data/panel
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY server ./server
COPY dist ./dist
EXPOSE 3000
CMD ["node", "server/index.js"]
