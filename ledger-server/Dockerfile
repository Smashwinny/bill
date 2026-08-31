FROM python:3.12-alpine
WORKDIR /app
COPY server.py /app/server.py
RUN adduser -D -u 10001 ledger && mkdir -p /data && chown ledger:ledger /data
USER ledger
ENV HOST=0.0.0.0 PORT=8790 LEDGER_DB=/data/ledger.sqlite3
EXPOSE 8790
VOLUME ["/data"]
CMD ["python3", "/app/server.py"]
