# Validation toolchain for the generated n8n workflows: python3 (to drive check_workflows.py) plus
# node + eslint (to actually parse and scope-check every Code node's JS).
#
# It lives in an image because the host is not required to have node — mh does not — and because
# `npx eslint` otherwise downloads eslint on every run. Pinning it here makes validation reproducible
# and offline after the first build.
FROM node:22-alpine

RUN apk add --no-cache python3

# Global so check_workflows.py finds `eslint` on PATH without a per-run download.
RUN npm install -g eslint@9.15.0 \
    && ln -sf /usr/local/lib/node_modules /node_modules

# eslint resolves its config's plugins from the CWD; the flat config check_workflows.py writes is
# self-contained, so only the binary needs to be resolvable.
ENV NODE_PATH=/usr/local/lib/node_modules

WORKDIR /work
CMD ["python3", "check_workflows.py"]
