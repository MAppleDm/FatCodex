FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NUTRITION_DB_PATH=/data/foods.db

WORKDIR /srv
COPY pyproject.toml ./
COPY nutrition ./nutrition
RUN pip install --no-cache-dir . \
    && useradd --system --no-create-home app \
    && mkdir /data && chown app /data

USER app
VOLUME /data
EXPOSE 8000
CMD ["uvicorn", "nutrition.main:app", "--host", "0.0.0.0", "--port", "8000"]
