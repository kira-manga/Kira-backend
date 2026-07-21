#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: restore-media.sh BACKUP.media.tar.gz EMPTY_TARGET_DIRECTORY" >&2
  exit 64
fi
archive=$1
target=$2
case "$archive" in /*.media.tar.gz) ;; *) echo "media archive must be an absolute .media.tar.gz path" >&2; exit 64 ;; esac
case "$target" in /*) ;; *) echo "target directory must be absolute" >&2; exit 64 ;; esac
case "$target" in /|/var|/home|/opt|/usr) echo "refusing broad target directory" >&2; exit 64 ;; esac
if [ "${KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST:-}" != "yes" ]; then
  echo "refusing media restore: set KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST=yes" >&2
  exit 64
fi
if [ ! -d "$target" ] || [ -n "$(find "$target" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "refusing media restore: target must exist and be empty" >&2
  exit 64
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check "$archive.sha256"
else
  shasum -a 256 --check "$archive.sha256"
fi
if tar -tzf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "refusing media archive with unsafe paths" >&2
  exit 65
fi
tar -C "$target" -xzf "$archive"
echo "tutorial media restored into empty target: $target"
