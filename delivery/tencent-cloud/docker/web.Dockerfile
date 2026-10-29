FROM node:22-bookworm-slim
WORKDIR /app
COPY --chown=node:node public ./public
COPY --chown=node:node entry-public ./entry-public
COPY --chown=node:node scripts ./scripts
USER node
ENV NODE_ENV=production
CMD ["node", "scripts/external-server.mjs"]
