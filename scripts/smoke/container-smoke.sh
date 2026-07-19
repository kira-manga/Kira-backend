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
mkdir -p "$smoke_temp_root"
temporary_directory=$(mktemp -d "$smoke_temp_root/kira-release-smoke.XXXXXX")

cleanup() {
  docker rm --force "$application" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  find "$temporary_directory" -type f -exec chmod 600 {} \; 2>/dev/null || true
  rm -rf "$temporary_directory"
}
trap cleanup EXIT INT TERM

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

docker network create "$network" >/dev/null
docker run --detach --name "$database" --network "$network" \
  --env POSTGRES_DB=kira --env POSTGRES_USER=kira --env "POSTGRES_PASSWORD=$database_password" \
  --volume "$temporary_directory:/source-certs:ro" \
  postgres:17.6-alpine \
  sh -ec 'mkdir -p /run/kira-certs; cp /source-certs/ca.crt /source-certs/server.crt /source-certs/server.key /run/kira-certs/; chown -R postgres:postgres /run/kira-certs; chmod 600 /run/kira-certs/server.key; exec docker-entrypoint.sh postgres -c ssl=on -c ssl_ca_file=/run/kira-certs/ca.crt -c ssl_cert_file=/run/kira-certs/server.crt -c ssl_key_file=/run/kira-certs/server.key' \
  >/dev/null

for _ in $(seq 1 45); do
  if docker exec "$database" pg_isready --username kira --dbname kira >/dev/null 2>&1; then
    break
  fi
  if [[ $(docker inspect --format '{{.State.Running}}' "$database") != true ]]; then
    docker logs "$database" >&2
    exit 1
  fi
  sleep 1
done
docker exec "$database" pg_isready --username kira --dbname kira >/dev/null

docker run --rm --network "$network" --entrypoint java \
  --volume "$temporary_directory/ca.crt:/run/kira-smoke/ca.crt:ro" \
  --env "KIRA_MIGRATION_DB_URL=$jdbc_url" --env KIRA_MIGRATION_DB_USERNAME=kira \
  --env "KIRA_MIGRATION_DB_PASSWORD=$database_password" \
  "$image" -Dloader.main=me.manga.kira.backend.database.DatabaseMigrationMain \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher >/dev/null

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
