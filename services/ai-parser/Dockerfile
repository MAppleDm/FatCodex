FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /srv
COPY pyproject.toml ./
COPY ai_parser ./ai_parser
RUN pip install --no-cache-dir . \
    && useradd --system --no-create-home app

USER app
EXPOSE 8000
CMD ["uvicorn", "ai_parser.main:app", "--host", "0.0.0.0", "--port", "8000"]
