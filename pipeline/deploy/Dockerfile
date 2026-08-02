# n8n + a static docker CLI, so the dispatcher workflow can spawn sibling bugfind
# containers on the host via the mounted docker socket. The n8n image is a hardened
# Alpine with no package manager, so we drop in the portable static docker binary.
FROM n8nio/n8n:latest
USER root
RUN wget -qO /tmp/docker.tgz https://download.docker.com/linux/static/stable/x86_64/docker-27.3.1.tgz \
    && tar -xzf /tmp/docker.tgz -C /usr/local/bin --strip-components=1 docker/docker \
    && rm /tmp/docker.tgz \
    && docker --version
# stays root so it can read /var/run/docker.sock (bind-mounted from the host)
