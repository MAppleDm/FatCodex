FROM python:3.12-slim

# The service user has no home directory, so keep every Hugging Face cache inside the /models volume.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    HF_HOME=/models/.hf \
    STT_MODEL_DIR=/models

WORKDIR /srv
COPY pyproject.toml ./
COPY stt ./stt
RUN pip install --no-cache-dir . \
    && useradd --system --no-create-home app \
    && mkdir /models && chown app /models

USER app
# The model (~470 MB for "small") is downloaded on first start and cached here.
VOLUME /models
EXPOSE 8000
CMD ["uvicorn", "stt.main:app", "--host", "0.0.0.0", "--port", "8000"]
