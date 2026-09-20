#!/bin/sh
# Is Clashfinder's data worth proxying? Answer that before writing a proxy.
#
# authPublicKey is sha256(username + privateKey + authParam + authValidUntil), plain
# concatenation, no separator — read off the key generator on the API page rather than
# from the prose, which does not say the order. The Rust proxy hardcodes the same.
#
#   ./clashprobe.sh              # list of clashfinders
#   ./clashprobe.sh gl2026       # one clashfinder
#
# Credentials come from $STS_HOME/clashfinder — the same state dir identity.p8 lives in,
# for the same reason: it is the machine's, not the repo's. Two lines, chmod 600:
#
#   CF_USER=yourname
#   CF_KEY=yourprivatekey
#
# The private key is off the account page and is not the password. Env overrides the file.

set -eu

creds="${STS_HOME:-$HOME/.station-to-station}/clashfinder"
if [ -r "$creds" ]; then
  # shellcheck source=/dev/null
  . "$creds"
fi

: "${CF_USER:?no username: write $creds or set CF_USER}"
: "${CF_KEY:?no private key: write $creds or set CF_KEY}"

base=https://clashfinder.com/data
out=${CF_OUT:-./clashfinder-samples}
mkdir -p "$out"

sha256() { printf %s "$1" | sha256sum | cut -d' ' -f1; }

key=$(sha256 "$CF_USER$CF_KEY")

fetch() { # fetch <path> <file>
  code=$(curl -s --max-time 60 -w '%{http_code}' \
    "$base/$1?authUsername=$CF_USER&authPublicKey=$key" -o "$out/$2")
  echo "$out/$2 — HTTP $code, $(wc -c <"$out/$2") bytes"
  [ "$code" = 200 ] || return 1
}

if [ $# -ge 1 ]; then
  fetch "event/$1.json" "$1.json"
else
  fetch events/events.json events-core.json
  fetch events/all.json events-all.json
fi
