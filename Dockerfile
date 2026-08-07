# Build the backend jar and the web client, run both from one small JRE image.

# Backend build. Dependencies resolve in their own layer so a src-only change does not re-download the world.
FROM eclipse-temurin:25-jdk AS backend
WORKDIR /build
# Without unzip the wrapper fetches the .tar.gz distribution instead of the .zip, and its checksum then
# never matches the pinned .zip sum. Give it unzip so it downloads the variant the pin is for.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY mvnw pom.xml ./
COPY .mvn .mvn
# A Windows checkout can carry CRLF into mvnw and the wrapper properties; normalize so the shebang runs
# and the pinned checksum has no trailing carriage return.
RUN sed -i 's/\r//g' mvnw .mvn/wrapper/maven-wrapper.properties && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
# Tests run in CI. The image build is packaging, not the gate.
RUN ./mvnw -q -B -DskipTests package

FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ .
# The same-origin demo needs a token baked into the bundle so create and transfer work. This is a demo
# token, not protection: anyone who opens the page has it. compose passes the developer's own value from
# .env, so no credential is committed.
ARG VITE_API_TOKEN
ENV VITE_API_TOKEN=$VITE_API_TOKEN
RUN npm run build

FROM eclipse-temurin:25-jre
# A money service has no business running as root.
RUN useradd --system --no-create-home tally
WORKDIR /app
COPY --from=backend /build/target/tally.jar app.jar
COPY --from=web /web/dist public
# Migrations are filesystem files read at boot. Without these two lines the app starts against an empty
# migrations dir and every query fails on missing tables.
COPY db/migrations /app/db/migrations
ENV TALLY_MIGRATIONS_DIR=/app/db/migrations
# The built client lives here; serving it makes the whole app one origin.
ENV TALLY_STATIC_DIR=/app/public
USER tally
EXPOSE 8080
# The probe is our own class, so the image needs no curl and the check proves the HTTP layer answers,
# not just that the port is open.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["java", "-cp", "app.jar", "dev.tally.ops.HealthProbe"]
ENTRYPOINT ["java", "-jar", "app.jar"]
