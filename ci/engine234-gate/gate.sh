#!/usr/bin/env bash
# Gate09 derivative: one exact Backend17 consumer, accepted artifact, no producer or lock writes.
set -euo pipefail
umask 077
C=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ ${GITHUB_RUN_ID:?} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:?} == 1 ]]
W=$(realpath -- "${GITHUB_WORKSPACE:?}")
T=$(realpath -- "${RUNNER_TEMP:?}")
R="$T/engine234-backend17-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
E="$R/evidence"
OWNER="$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT"
VERSION=0.1.0-engine234-8cb9ead84983-macos-35018546402-1
LOCK=f300f729846e9794f0342e750d3c6a7a5dfc28ae527fd23e8a1e2fab554dba9e
MODE=${1:?}

if [[ $MODE == setup ]]; then
  [[ ${GITHUB_REPOSITORY:?} == kira-manga/Kira-backend && ${GITHUB_EVENT_NAME:?} == push &&
     ${GITHUB_REF:?} == refs/heads/remediation/engine234-backend-consumer-20260915-01 ]]
  python3 - "${GITHUB_EVENT_PATH:?}" <<'EVENT'
import json, sys
with open(sys.argv[1], encoding="utf-8") as event:
    repository = json.load(event)["repository"]
assert repository["full_name"] == "kira-manga/Kira-backend" and repository["private"] is False
EVENT
  [[ ! -e $R && ! -L $R && ! -e $W/inputs && ! -L $W/inputs ]]
  [[ -x ${JAVA_HOME_21_X64:?}/bin/java ]]
  mkdir -- "$R" "$W/inputs"
  printf '%s\n' "$OWNER" > "$R/.owner"
  printf '%s\n' "$OWNER" > "$W/inputs/.engine234-owner"
  mkdir -- "$E" "$R/maven" "$R/tmp" "$R/home" "$R/backend-gradle" "$R/backend-cache"
  printf '%s\n' "$VERSION" > "$R/version.txt"
  date +%s > "$R/started"
  printf 'ENGINE234_RUN_ROOT=%s\nJAVA_HOME=%s\n' "$R" "$JAVA_HOME_21_X64" >> "$GITHUB_ENV"
  printf '%s\n' "$JAVA_HOME_21_X64/bin" >> "$GITHUB_PATH"
  { df -PB1 "$R"; free -b; } > "$E/resources-initial.txt"
  exit 0
fi

[[ ${ENGINE234_RUN_ROOT:?} == "$R" && ! -L $R && $(realpath -- "$R") == "$R" ]]
[[ $(cat "$R/.owner") == "$OWNER" && $(cat "$R/version.txt") == "$VERSION" ]]
[[ ! -L $W/inputs && $(cat "$W/inputs/.engine234-owner") == "$OWNER" ]]
[[ -x ${JAVA_HOME:?}/bin/java ]]
BASE_ENV=(env -i "PATH=$PATH" "JAVA_HOME=$JAVA_HOME" "HOME=$R/home" "TMPDIR=$R/tmp"
  "LANG=C.UTF-8" "TZ=UTC" "ENGINE234_RUN_ROOT=$R" "XDG_CACHE_HOME=$R/home/.cache"
  "JAVA_TOOL_OPTIONS=-Xmx768m -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home")
DOCKER=("${BASE_ENV[@]}" DOCKER_HOST=unix:///var/run/docker.sock docker)

capture() {
  local label=$1 duration=$2 started
  shift 2
  started=$(date +%s)
  set +e
  timeout --signal=TERM --kill-after=10s "$duration" "$@" 2>&1 | python3 "$C/cap.py" "$E/$label.log"
  local codes=("${PIPESTATUS[@]}")
  set -e
  printf '%s %s\n' "${codes[0]}" "${codes[1]}" > "$E/$label.exit"
  printf '{"deadline":"%s","kill_grace_seconds":10,"started_epoch":%s,"finished_epoch":%s}\n' \
    "$duration" "$started" "$(date +%s)" > "$E/$label.deadline.json"
  [[ ${codes[0]} == 0 && ${codes[1]} == 0 ]]
}

remove_owned() {
  local path=$1
  case "$path" in
    "$R/"*) [[ $path != "$E" && $path != "$R/.owner" ]] || return 1 ;;
    "$W/inputs/backend") ;;
    *) return 1 ;;
  esac
  [[ ! -L $path && $(realpath -m -- "$path") == "$path" ]] || return 1
  rm -rf --one-file-system -- "$path"
}

stop_owned() {
  local label=$1
  shopt -s nullglob
  local launchers=("$R/backend-gradle/wrapper/dists/gradle-8.14.5-bin/"*/gradle-8.14.5/bin/gradle)
  shopt -u nullglob
  ((${#launchers[@]} <= 1)) || return 1
  if ((${#launchers[@]} == 0)); then
    printf 'NO_INSTALLED_OWNED_DISTRIBUTION\n' > "$E/$label.txt"
    return 0
  fi
  capture "$label" 15s "${BASE_ENV[@]}" "GRADLE_USER_HOME=$R/backend-gradle" \
    "${launchers[0]}" --stop -g "$R/backend-gradle" --console=plain
}

close_backend() {
  local rc=0 quiet=0 name
  [[ ! -f $E/backend-cleanup.exit ]] || return "$(cat "$E/backend-cleanup.exit")"
  if [[ -f $E/backend.started ]]; then
    stop_owned backend-stop-before || rc=1
    python3 "$C/quiet.py" backend-before || { rc=1; quiet=1; }
    capture docker-events 10s "${DOCKER[@]}" events \
      --since "$(cat "$E/backend.started")" --until "$(date +%s)" \
      --filter type=container --filter label=org.testcontainers=true \
      --format '{{.Action}} {{.ID}} {{index .Actor.Attributes "image"}} {{index .Actor.Attributes "org.testcontainers.sessionId"}}' || rc=1
    # Fresh XML, source/lock and candidate readback precede any source/cache reclamation.
    python3 "$C/proof.py" backend || rc=1
    if [[ $quiet == 0 ]]; then
      for name in build .gradle .kotlin; do
        [[ ! -L $W/inputs/backend && $(realpath -m "$W/inputs/backend/$name") == "$W/inputs/backend/$name" ]] || return 1
        rm -rf --one-file-system -- "$W/inputs/backend/$name"
      done
      for name in backend-cache tmp home backend-gradle/caches; do remove_owned "$R/$name" || rc=1; done
      mkdir -p -- "$R/tmp" "$R/home"
    fi
    stop_owned backend-stop-after || rc=1
    python3 "$C/quiet.py" backend-after || rc=1
  fi
  printf '%s\n' "$rc" > "$E/backend-cleanup.exit"
  return "$rc"
}

case "$MODE" in
  candidate)
    python3 "$C/proof.py" bind
    # Token exists in this one Actions-read step only, never in BASE_ENV/Gradle or receipts.
    export GH_PROMPT_DISABLED=1 GH_CONFIG_DIR="$R/home/.config/gh"
    [[ -n ${GH_TOKEN:?} ]]
    capture producer-run 20s gh api repos/kira-manga/Kira-backend/actions/runs/35018546402 \
      --jq '{id,head_sha,head_branch,status,conclusion,event,run_attempt,repository:{full_name:.repository.full_name,private:.repository.private}}'
    capture artifact-metadata 20s gh api repos/kira-manga/Kira-backend/actions/artifacts/10416542685 \
      --jq '{id,name,size_in_bytes,digest,expired,expires_at,workflow_run}'
    capture artifact-download 45s bash -c 'ulimit -f 8192; exec gh api "$1" > "$2"' _ \
      repos/kira-manga/Kira-backend/actions/artifacts/10416542685/zip "$R/candidate.zip"
    python3 "$C/proof.py" candidate
    ;;
  prerequisites)
    [[ -f $E/candidate-result.json ]]
    capture java 10s "${BASE_ENV[@]}" "$JAVA_HOME/bin/java" -version
    grep -Eq 'version "21[.\"]' "$E/java.log"
    [[ $(df -PB1 "$R" | awk 'NR==2 {print $4}') -ge 8589934592 ]]
    [[ $(awk '/^MemAvailable:/ {print $2}' /proc/meminfo) -ge 5242880 ]]
    capture docker-info 10s "${DOCKER[@]}" version
    capture docker-baseline 10s "${DOCKER[@]}" ps -aq --no-trunc
    [[ ! -s $E/docker-baseline.log ]]
    capture postgres-pull 75s "${DOCKER[@]}" pull postgres:17.6-alpine
    capture service-images 10s "${DOCKER[@]}" image inspect postgres:17.6-alpine \
      --format '{{.Id}} {{json .RepoTags}} {{json .RepoDigests}}'
    ;;
  backend)
    [[ -f $E/candidate-result.json && ! -e $E/backend.started ]]
    [[ -d $W/inputs/backend && ! -L $W/inputs/backend ]]
    # Stop build work by setup+10 minutes, reserving cleanup/upload inside the 15-minute job.
    DURATION=$(( $(cat "$R/started") + 600 - $(date +%s) ))
    ((DURATION > 0))
    ((DURATION <= 420)) || DURATION=420
    TASKS=(./gradlew --no-daemon --no-parallel --max-workers=2 --no-build-cache
      --no-configuration-cache --console=plain --stacktrace --project-cache-dir "$R/backend-cache"
      -Pkotlin.compiler.execution.strategy=in-process -Porg.gradle.java.installations.auto-download=false
      -Dorg.gradle.vfs.watch=false
      "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home"
      -I "$C/engine234.init.gradle" -Pengine234Role=backend "-Pengine234Version=$VERSION"
      "-Pengine234RunRoot=$R" "-Pengine234Repository=$R/maven"
      -Pengine234Phase=test "-Pengine234LockSha256=$LOCK" -PkiraUseMavenLocal=false
      test -x jacocoTestReport)
    while IFS= read -r class; do TASKS+=(--tests "$class"); done < "$C/backend-test-classes.txt"
    printf '%q ' "${TASKS[@]}" > "$E/backend-command.txt"
    printf '\n' >> "$E/backend-command.txt"
    date +%s > "$E/backend.started"
    cd -- "$W/inputs/backend"
    capture backend "${DURATION}s" "${BASE_ENV[@]}" "GRADLE_USER_HOME=$R/backend-gradle" \
      DOCKER_HOST=unix:///var/run/docker.sock "${TASKS[@]}"
    ;;
  finish)
    RC=0
    close_backend || RC=1
    # Gate09 ownership rule: Ryuk enabled; forced/unknown residue fails, never prune globally.
    DOCKER_RC=0
    if [[ -f $E/docker-baseline.exit && $(cat "$E/docker-baseline.exit") == '0 0' && ! -s $E/docker-baseline.log ]]; then
      sleep 3
      capture docker-residue 10s "${DOCKER[@]}" ps -aq --no-trunc || DOCKER_RC=1
      if [[ -s $E/docker-residue.log ]]; then
        DOCKER_RC=1
        REMOVE_IDS=()
        while IFS= read -r id; do
          [[ $id =~ ^[0-9a-f]{64}$ ]] || continue
          if [[ -f $E/docker-events.log ]] && awk -v id="$id" '$2==id && ($1=="create" || $1=="start") {found=1} END {exit !found}' "$E/docker-events.log"; then
            REMOVE_IDS+=("$id")
          fi
        done < "$E/docker-residue.log"
        if ((${#REMOVE_IDS[@]} > 0 && ${#REMOVE_IDS[@]} <= 16)); then
          capture docker-remove-owned 15s "${DOCKER[@]}" rm -f "${REMOVE_IDS[@]}" || DOCKER_RC=1
        fi
      fi
      capture docker-final 10s "${DOCKER[@]}" ps -aq --no-trunc || DOCKER_RC=1
      [[ ! -s $E/docker-final.log ]] || DOCKER_RC=1
    else
      DOCKER_RC=1
    fi
    printf '%s\n' "$DOCKER_RC" > "$E/docker-cleanup.exit"
    [[ $DOCKER_RC == 0 ]] || RC=1
    if python3 "$C/quiet.py" final; then
      for name in backend-gradle backend-cache tmp home maven candidate.zip; do remove_owned "$R/$name" || RC=1; done
      remove_owned "$W/inputs/backend" || RC=1
    else
      RC=1
    fi
    { df -PB1 "$R"; free -b; } > "$E/resources-final.txt"
    printf '%s\n' "$RC" > "$E/final-cleanup.exit"
    python3 "$C/proof.py" seal
    ;;
  *) printf 'Unknown fixed consumer gate phase\n' >&2; exit 2 ;;
esac
