# The provided zero-dependency mock provider (scripts/mock_provider.py), unmodified.
FROM python:3.12-slim
WORKDIR /app
COPY scripts/mock_provider.py /app/mock_provider.py
ENTRYPOINT ["python3", "-u", "/app/mock_provider.py"]
