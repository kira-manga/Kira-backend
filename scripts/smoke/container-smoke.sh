#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 IMAGE" >&2
  exit 64
fi

image=$1
suffix="$$"
network="kira-release-smoke-$suffix"
database="kira-release-smoke-postgres-$suffix"
application="kira-release-smoke-app-$suffix"
smoke_temp_root=${KIRA_SMOKE_TEMP_ROOT:-"$PWD/build/tmp"}
postgres_image="postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94"
failure_stage=initialization
mkdir -p "$smoke_temp_root"
temporary_directory=$(mktemp -d "$smoke_temp_root/kira-release-smoke.XXXXXX")

redact_stream() {
  local line sensitive
  while IFS= read -r line; do
    for sensitive in "${database_password:-}" "${jwt_secret:-}" "${signing_private:-}"; do
      if [[ -n $sensitive ]]; then
        line=${line//"$sensitive"/[REDACTED]}
      fi
    done
    printf '%s\n' "$line"
  done
}

cleanup() {
  docker rm --force "$application" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  find "$temporary_directory" -type f -exec chmod 600 {} \; 2>/dev/null || true
  rm -rf "$temporary_directory"
}
trap cleanup EXIT INT TERM

report_failure() {
  local status=$1
  local line=$2
  trap - ERR
  echo "production-profile container smoke failed during $failure_stage (line $line, status $status)" >&2
  if [[ -f $temporary_directory/migration.log ]]; then
    redact_stream < "$temporary_directory/migration.log" >&2
  fi
  if docker inspect "$database" >/dev/null 2>&1; then
    docker logs "$database" 2>&1 | redact_stream >&2 || true
  fi
  if docker inspect "$application" >/dev/null 2>&1; then
    docker logs "$application" 2>&1 | redact_stream >&2 || true
  fi
  exit "$status"
}
trap 'report_failure "$?" "$LINENO"' ERR

failure_stage="ephemeral TLS and signing material generation"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$temporary_directory/ca.key" -out "$temporary_directory/ca.crt" \
  -subj "/CN=Kira release smoke CA" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes \
  -keyout "$temporary_directory/server.key" -out "$temporary_directory/server.csr" \
  -subj "/CN=$database" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$database" > "$temporary_directory/server.ext"
openssl x509 -req -days 1 -sha256 \
  -in "$temporary_directory/server.csr" -CA "$temporary_directory/ca.crt" \
  -CAkey "$temporary_directory/ca.key" -CAcreateserial \
  -extfile "$temporary_directory/server.ext" -out "$temporary_directory/server.crt" >/dev/null 2>&1

mkdir -p "$temporary_directory/signing"
scripts/signing/generate-key.sh smoke "$temporary_directory/signing" >/dev/null
signing_private=$(tr -d '\n' < "$temporary_directory/signing/smoke.private.b64")
signing_public=$(tr -d '\n' < "$temporary_directory/signing/smoke.public.b64")
jwt_secret=$(openssl rand -base64 48 | tr -d '\n')
database_password=$(openssl rand -hex 24)
jdbc_url="jdbc:postgresql://$database:5432/kira?sslmode=verify-full&sslrootcert=/run/kira-smoke/ca.crt"

failure_stage="disposable PostgreSQL startup"
docker network create "$network" >/dev/null
docker run --detach --name "$database" --network "$network" \
  --env POSTGRES_DB=kira --env POSTGRES_USER=kira --env "POSTGRES_PASSWORD=$database_password" \
  --volume "$temporary_directory:/source-certs:ro" \
  "$postgres_image" \
  sh -ec 'mkdir -p /run/kira-certs; cp /source-certs/ca.crt /source-certs/server.crt /source-certs/server.key /run/kira-certs/; chown -R postgres:postgres /run/kira-certs; chmod 600 /run/kira-certs/server.key; exec docker-entrypoint.sh postgres -c ssl=on -c ssl_ca_file=/run/kira-certs/ca.crt -c ssl_cert_file=/run/kira-certs/server.crt -c ssl_key_file=/run/kira-certs/server.key' \
  >/dev/null

database_ready=false
for _ in $(seq 1 45); do
  if docker exec "$database" sh -ec 'test "$(cat /proc/1/comm)" = postgres' >/dev/null 2>&1 &&
    docker exec "$database" psql --username kira --dbname kira --tuples-only --command 'SELECT 1' >/dev/null 2>&1; then
    database_ready=true
    break
  fi
  if [[ $(docker inspect --format '{{.State.Running}}' "$database") != true ]]; then
    docker logs "$database" >&2
    exit 1
  fi
  sleep 1
done
if [[ $database_ready != true ]]; then
  docker logs "$database" >&2
  echo "disposable PostgreSQL did not complete initialization" >&2
  exit 1
fi

failure_stage="database migration"
docker run --rm --network "$network" --entrypoint java \
  --volume "$temporary_directory/ca.crt:/run/kira-smoke/ca.crt:ro" \
  --env "KIRA_MIGRATION_DB_URL=$jdbc_url" --env KIRA_MIGRATION_DB_USERNAME=kira \
  --env "KIRA_MIGRATION_DB_PASSWORD=$database_password" \
  "$image" -Dloader.main=me.manga.kira.backend.database.DatabaseMigrationMain \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  > "$temporary_directory/migration.log" 2>&1

failure_stage="production application startup"
docker run --detach --name "$application" --network "$network" \
  --volume "$temporary_directory/ca.crt:/run/kira-smoke/ca.crt:ro" \
  --env "SPRING_DATASOURCE_URL=$jdbc_url" --env SPRING_DATASOURCE_USERNAME=kira \
  --env "SPRING_DATASOURCE_PASSWORD=$database_password" --env "KIRA_JWT_SECRET=$jwt_secret" \
  --env KIRA_SECURITY_EXTERNAL_BASE_URL=https://api.smoke.invalid \
  --env KIRA_SECURITY_THROTTLE_BACKEND=memory --env KIRA_SECURITY_THROTTLE_INSTANCE_COUNT=1 \
  --env KIRA_ADMIN_SEED_ENABLED=false --env KIRA_COMPLETION_ENABLED=false \
  --env KIRA_SIGNING_ACTIVE_KEY_ID=smoke --env "KIRA_SIGNING_PRIVATE_KEY=$signing_private" \
  --env KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID=smoke \
  --env "KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY=$signing_public" \
  "$image" >/dev/null

failure_stage="readiness, liveness, and metrics probes"
for _ in $(seq 1 60); do
  if docker exec "$application" wget -q -O /dev/null http://127.0.0.1:9090/actuator/health/readiness; then
    docker exec "$application" wget -q -O /dev/null http://127.0.0.1:9090/actuator/health/liveness
    docker exec "$application" wget -q -O /dev/null http://127.0.0.1:9090/actuator/prometheus
    echo "production-profile container smoke passed"
    exit 0
  fi
  if [[ $(docker inspect --format '{{.State.Running}}' "$application") != true ]]; then
    docker logs "$application" >&2
    exit 1
  fi
  sleep 2
done

docker logs "$application" >&2
echo "production-profile container did not become ready" >&2
exit 1
