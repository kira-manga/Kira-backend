#!/usr/bin/env bash
# One immutable public-source verification carrier. No deployment, credentials or global cleanup.
set -euo pipefail
umask 077
C=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ ${GITHUB_RUN_ID:?} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:?} =~ ^[0-9]+$ ]]
W=$(realpath -- "${GITHUB_WORKSPACE:?}")
T=$(realpath -- "${RUNNER_TEMP:?}")
R="$T/auth30-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
E="$R/evidence"
OWNER="$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT"
MODE=${1:?}
if [[ $MODE == setup ]]; then
  [[ ${GITHUB_REPOSITORY:?} == kira-manga/Kira-backend && ${GITHUB_EVENT_NAME:?} == push &&
     ${GITHUB_REF:?} == refs/heads/remediation/auth30-hosted-20260916-01 && $GITHUB_RUN_ATTEMPT == 1 ]]
  python3 - "${GITHUB_EVENT_PATH:?}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    repository = json.load(source)["repository"]
assert repository["full_name"] == "kira-manga/Kira-backend" and repository["private"] is False
PY
  [[ ! -e $R && ! -L $R && ! -e $W/auth30-inputs && ! -L $W/auth30-inputs && -x ${JAVA_HOME:?}/bin/java ]]
  mkdir -- "$R" "$W/auth30-inputs"
  printf '%s\n' "$OWNER" > "$R/.owner"
  printf '%s\n' "$OWNER" > "$W/auth30-inputs/.owner"
  mkdir -- "$E" "$R/maven" "$R/tmp" "$R/home" "$R/konan" \
    "$R/engine-gradle" "$R/backend-gradle" "$R/engine-cache" "$R/backend-cache"
  printf 'AUTH30_RUN_ROOT=%s\n' "$R" >> "$GITHUB_ENV"
  { df -PB1 "$R"; free -b; } > "$E/resources-initial.txt"
  exit 0
fi
[[ ${AUTH30_RUN_ROOT:?} == "$R" && ! -L $R && $(realpath -- "$R") == "$R" ]]
[[ $(cat "$R/.owner") == "$OWNER" && ! -L $W/auth30-inputs && $(cat "$W/auth30-inputs/.owner") == "$OWNER" ]]
BASE_ENV=(env -i "PATH=$PATH" "JAVA_HOME=${JAVA_HOME:?}" "HOME=$R/home" "TMPDIR=$R/tmp"
  "LANG=C.UTF-8" "TZ=UTC" "AUTH30_RUN_ROOT=$R" "XDG_CACHE_HOME=$R/home/.cache"
  "JAVA_TOOL_OPTIONS=-Xmx768m -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home")
DOCKER=("${BASE_ENV[@]}" DOCKER_HOST=unix:///var/run/docker.sock docker)

capture() {
  local label=$1 duration=$2
  shift 2
  set +e
  timeout --signal=TERM --kill-after=10s "$duration" "$@" 2>&1 | python3 "$C/cap.py" "$E/$label.log"
  local codes=("${PIPESTATUS[@]}")
  set -e
  printf '%s %s\n' "${codes[0]}" "${codes[1]}" > "$E/$label.exit"
  if [[ ${codes[0]} != 0 || ${codes[1]} != 0 ]]; then
    tail -80 "$E/$label.log"
    return 1
  fi
}
remove_owned() {
  local path=$1
  case "$path" in
    "$R/"*) [[ $path != "$E" && $path != "$R/.owner" ]] ;;
    "$W/auth30-inputs/engine"|"$W/backend/build"|"$W/backend/.gradle"|"$W/backend/.kotlin") ;;
    *) return 1 ;;
  esac
  [[ ! -L $path && $(realpath -m -- "$path") == "$path" ]] || return 1
  rm -rf --one-file-system -- "$path"
}
stop_owned() {
  local stage=$1 label=$2 distribution=8.14.5
  [[ $stage != engine ]] || distribution=9.6.1
  [[ $stage != pgjdbc ]] || distribution=9.4.1
  shopt -s nullglob
  local launchers=("$R/$stage-gradle/wrapper/dists/gradle-$distribution-bin/"*/"gradle-$distribution/bin/gradle")
  shopt -u nullglob
  ((${#launchers[@]} <= 1)) || return 1
  if ((${#launchers[@]} == 0)); then
    printf 'NO_INSTALLED_OWNED_DISTRIBUTION\n' > "$E/$label.txt"
    return 0
  fi
  capture "$label" 30s "${BASE_ENV[@]}" "GRADLE_USER_HOME=$R/$stage-gradle" \
    "${launchers[0]}" --stop -g "$R/$stage-gradle" --console=plain
}
close_stage() {
  local stage=$1 rc=0 quiet=0
  if [[ -f $E/$stage-cleanup.exit ]]; then return "$(cat "$E/$stage-cleanup.exit")"; fi
  [[ -f $E/$stage.started ]] || return 0
  stop_owned "$stage" "$stage-stop-before" || rc=1
  python3 "$C/quiet.py" "$stage-before" || { rc=1; quiet=1; }
  if [[ $stage == backend ]]; then
    capture docker-events 20s "${DOCKER[@]}" events \
      --since "$(cat "$E/backend.started")" --until "$(date +%s)" \
      --filter type=container --filter label=org.testcontainers=true \
      --format '{{.Action}} {{.ID}} {{index .Actor.Attributes "image"}} {{index .Actor.Attributes "org.testcontainers.sessionId"}}' || rc=1
  fi
  python3 "$C/proof.py" "$stage" || rc=1
  if [[ $quiet == 0 ]]; then
    if [[ $stage == engine ]]; then
      remove_owned "$W/auth30-inputs/engine" || rc=1
    else
      if [[ -f $E/backend-reports-preserved.json && ! -L $E/backend-reports-preserved.json ]]; then
        remove_owned "$W/backend/build" || rc=1
      else
        printf 'Raw-report preservation incomplete; backend build retained.\n' > "$E/backend-build-retained.txt"
        rc=1
      fi
      for path in .gradle .kotlin; do remove_owned "$W/backend/$path" || rc=1; done
    fi
    for path in "$stage-cache" "$stage-gradle/caches" konan; do remove_owned "$R/$path" || rc=1; done
    mkdir -p -- "$R/konan"
  fi
  stop_owned "$stage" "$stage-stop-after" || rc=1
  if python3 "$C/quiet.py" "$stage-after"; then remove_owned "$R/$stage-gradle" || rc=1; else rc=1; fi
  printf '%s\n' "$rc" > "$E/$stage-cleanup.exit"
  return "$rc"
}
run_gradle() {
  local stage=$1 duration=$2 checkout=$3
  shift 3
  [[ ! -e $E/$stage.started && -d $checkout && ! -L $checkout ]]
  date +%s > "$E/$stage.started"
  cd -- "$checkout"
  capture "$stage" "$duration" "${BASE_ENV[@]}" \
    "GRADLE_USER_HOME=$R/$stage-gradle" "KONAN_DATA_DIR=$R/konan" \
    "ANDROID_HOME=${ANDROID_HOME:?}" "ANDROID_SDK_ROOT=${ANDROID_HOME:?}" \
    "ANDROID_USER_HOME=$R/home/.android" DOCKER_HOST=unix:///var/run/docker.sock \
    ./gradlew --no-daemon --no-parallel --max-workers=1 --no-build-cache \
    --no-configuration-cache --no-scan --console=plain --stacktrace \
    --project-cache-dir "$R/$stage-cache" \
    -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.native.jvmArgs=-Xmx768m \
    -Porg.gradle.java.installations.auto-download=false -Porg.gradle.java.installations.auto-detect=false \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" -Dorg.gradle.vfs.watch=false \
    "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home" \
    -I "$C/$stage.init.gradle" "$@"
}
case "$MODE" in
  prerequisites)
    python3 "$C/proof.py" bind
    capture java 10s "${BASE_ENV[@]}" "$JAVA_HOME/bin/java" -version
    grep -Eq 'version "21[.\"]' "$E/java.log"
    [[ $(df -PB1 "$R" | awk 'NR==2 {print $4}') -ge 12884901888 && -d ${ANDROID_HOME:?} ]]
    capture docker-info 30s "${DOCKER[@]}" version
    capture docker-baseline 20s "${DOCKER[@]}" ps -aq --no-trunc
    [[ ! -s $E/docker-baseline.log ]]
    capture postgres-pull 120s "${DOCKER[@]}" pull postgres:17.6-alpine
    capture redis-pull 120s "${DOCKER[@]}" pull redis:7.4.7-alpine
    capture service-images 20s "${DOCKER[@]}" image inspect postgres:17.6-alpine redis:7.4.7-alpine \
      --format '{{.Id}} {{json .RepoTags}} {{json .RepoDigests}}'
    ;;
  engine)
    TASKS=()
    for module in source-contract source-engine; do
      TASKS+=(":$module:publishKotlinMultiplatformPublicationToW01OnlyRepository" ":$module:publishJvmPublicationToW01OnlyRepository")
    done
    run_gradle engine 12m "$W/auth30-inputs/engine" -PVERSION_NAME=0.1.0 "${TASKS[@]}"
    ;;
  engine-close) close_stage engine ;;
  backend)
    [[ $(cat "$E/engine-cleanup.exit") == 0 && $(cat "$E/pgjdbc-cleanup.exit") == 0 ]]
    [[ $(df -PB1 "$R" | awk 'NR==2 {print $4}') -ge 4294967296 ]]
    TASKS=(compileKotlin compileTestKotlin detekt ktlintMainSourceSetCheck ktlintTestSourceSetCheck test -x jacocoTestReport
      -PkiraUseMavenLocal=false "-PkiraOwnedPgVerificationRepository=$R/maven")
    while IFS= read -r method; do TASKS+=(--tests "$method"); done < <(python3 -c \
      'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["selectors"]))' "$C/selection.json")
    run_gradle backend 15m "$W/backend" "${TASKS[@]}"
    ;;
  finish)
    RC=0
    close_stage engine || RC=1
    close_stage backend || RC=1
    # Also stop a supplier interrupted before its own trap could finish.
    stop_owned pgjdbc pgjdbc-stop-final || RC=1
    DOCKER_RC=0
    if [[ -f $E/docker-baseline.exit ]]; then
      sleep 5
      capture docker-residue 20s "${DOCKER[@]}" ps -aq --no-trunc || DOCKER_RC=1
      if [[ -s $E/docker-residue.log ]]; then
        DOCKER_RC=1
        REMOVE_IDS=()
        while IFS= read -r id; do
          [[ $id =~ ^[0-9a-f]{64}$ ]] || continue
          if [[ -f $E/docker-events.log ]] && awk -v id="$id" '$2==id && ($1=="create" || $1=="start") {found=1} END {exit !found}' "$E/docker-events.log"; then
            REMOVE_IDS+=("$id")
          fi
        done < "$E/docker-residue.log"
        if ((${#REMOVE_IDS[@]} > 0 && ${#REMOVE_IDS[@]} <= 32)); then
          capture docker-remove-owned 30s "${DOCKER[@]}" rm -f "${REMOVE_IDS[@]}" || DOCKER_RC=1
        fi
      fi
      capture docker-final 20s "${DOCKER[@]}" ps -aq --no-trunc || DOCKER_RC=1
      [[ ! -s $E/docker-final.log ]] || DOCKER_RC=1
    else DOCKER_RC=1; fi
    printf '%s\n' "$DOCKER_RC" > "$E/docker-cleanup.exit"
    [[ $DOCKER_RC == 0 ]] || RC=1
    if python3 "$C/quiet.py" final; then
      for path in engine-gradle backend-gradle pgjdbc-gradle engine-cache backend-cache pgjdbc-cache pgjdbc-kotlin \
          pgjdbc pgjdbc-archive.tar.gz konan tmp home maven; do remove_owned "$R/$path" || RC=1; done
      remove_owned "$W/auth30-inputs/engine" || RC=1
    else RC=1; fi
    printf '%s\n' "$RC" > "$E/final-cleanup.exit"
    python3 "$C/proof.py" seal
    ;;
  *) printf 'Unknown fixed gate phase\n' >&2; exit 2 ;;
esac
