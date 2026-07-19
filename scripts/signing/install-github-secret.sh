#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <repository> <key-id> <key-directory>" >&2
  exit 64
fi

repository=$1
key_id=$2
key_directory=$3
private_b64="$key_directory/$key_id.private.b64"
public_b64="$key_directory/$key_id.public.b64"
[[ -r $private_b64 && -r $public_b64 ]] || { echo "generated key files are missing" >&2; exit 66; }

# The private value is streamed on stdin and never appears in argv or terminal output.
gh secret set KIRA_SIGNING_PRIVATE_KEY --repo "$repository" < "$private_b64"
gh secret set KIRA_SIGNING_ACTIVE_KEY_ID --repo "$repository" --body "$key_id"
gh secret set KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID --repo "$repository" --body "$key_id"
gh secret set KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY --repo "$repository" < "$public_b64"
echo "Installed signing configuration for $key_id in $repository."
