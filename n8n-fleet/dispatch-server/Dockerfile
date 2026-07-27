# Minimal HTTP dispatch service: python + a static docker CLI (to talk to the host
# daemon via the mounted socket). Runs dispatch.sh on each GET.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates wget \
    && wget -qO /tmp/d.tgz https://download.docker.com/linux/static/stable/x86_64/docker-27.3.1.tgz \
    && tar -xzf /tmp/d.tgz -C /usr/local/bin --strip-components=1 docker/docker \
    && rm /tmp/d.tgz && apt-get purge -y wget && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
COPY server.py /app/server.py
EXPOSE 8080
CMD ["python", "-u", "/app/server.py"]
