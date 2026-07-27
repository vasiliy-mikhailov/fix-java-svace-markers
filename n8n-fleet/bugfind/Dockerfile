# Fresh, minimal per-repo bug-finder image. No OpenHands, no build tools -
# just Python stdlib + git. It clones a repo and calls the vLLM endpoint.
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY find_bugs.py /app/find_bugs.py

# REPO is passed as the CMD argument by the dispatcher:
#   docker run --rm bugfind <owner/repo | synth:name>
ENTRYPOINT ["python", "-u", "/app/find_bugs.py"]
