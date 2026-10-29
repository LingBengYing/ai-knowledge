FROM ghcr.io/astral-sh/uv:0.12.6 AS uv
FROM python:3.10-slim-bookworm
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --no-managed-python
COPY knowledge_agent ./knowledge_agent
COPY UPSTREAM.md ./UPSTREAM.md
RUN groupadd -g 10001 knowledge && useradd -u 10001 -g 10001 -M knowledge
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["/app/.venv/bin/python", "-m", "knowledge_agent"]
