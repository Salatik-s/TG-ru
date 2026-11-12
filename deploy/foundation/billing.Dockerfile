FROM node:24-bookworm-slim@sha256:c0a93797ee78814ae6d32fcca2561fdbbe423149c3f5389aad32d86e9fd85bd4

RUN install -d -m 0700 -o 10002 -g 10002 /data \
    && install -m 0600 -o 10002 -g 10002 /dev/null /data/.billing-volume

USER 10002:10002
