#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <key-id> <output-directory>" >&2
  exit 64
fi

key_id=$1
output_directory=$2
if [[ ! $key_id =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
  echo "key id must match [A-Za-z0-9._-]{1,64}" >&2
  exit 64
fi

mkdir -p "$output_directory"
chmod 700 "$output_directory"
private_der="$output_directory/$key_id.private.der"
private_b64="$output_directory/$key_id.private.b64"
public_der="$output_directory/$key_id.public.der"
public_b64="$output_directory/$key_id.public.b64"
for path in "$private_der" "$private_b64" "$public_der" "$public_b64"; do
  if [[ -e $path ]]; then
    echo "refusing to overwrite $path" >&2
    exit 73
  fi
done

umask 077
openssl_bin=${OPENSSL_BIN:-}
if [[ -z $openssl_bin ]]; then
  for candidate in /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl "$(command -v openssl)"; do
    if [[ -x $candidate ]] && "$candidate" list -public-key-algorithms 2>/dev/null | grep -qi Ed25519; then
      openssl_bin=$candidate
      break
    fi
  done
fi
if [[ -z $openssl_bin ]]; then
  echo "OpenSSL 3 with Ed25519 support is required (or set OPENSSL_BIN)" >&2
  exit 69
fi
"$openssl_bin" genpkey -algorithm Ed25519 -outform DER -out "$private_der"
"$openssl_bin" pkey -in "$private_der" -inform DER -pubout -outform DER -out "$public_der"
base64 < "$private_der" | tr -d '\n' > "$private_b64"
base64 < "$public_der" | tr -d '\n' > "$public_b64"
chmod 600 "$private_der" "$private_b64" "$public_der" "$public_b64"

echo "Generated $key_id. Private material is in $output_directory and was not printed."
echo "Public key: $(<"$public_b64")"
