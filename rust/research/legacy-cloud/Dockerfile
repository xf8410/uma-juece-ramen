FROM rust:1.80-slim AS builder
WORKDIR /build
COPY rust/ .
RUN cargo build --release --bin ramen_server

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/target/release/ramen_server /app/ramen_server
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
