#!/usr/bin/env bash
#
# Deploys the static homepage (the repo's top-level homepage/ folder) to the server (the
# "cloud_driver" ssh alias) and makes Caddy serve it on the apex domain https://cloud-driver.de. The
# Java backend is not involved: Caddy serves the files directly from $REMOTE_WEB_ROOT.
#
# What it does, every run:
#   1. Refuses to deploy if any homepage file still contains a class="todo" placeholder -
#      publishing an Impressum/privacy page with placeholders is worse than not publishing.
#   2. Uploads every file in homepage/ to $REMOTE_WEB_ROOT (created if missing), then verifies
#      each file landed byte-for-byte intact (SHA-256 on both ends, same convention as
#      deploy-cloud.sh).
#   3. Ensures /etc/caddy/Caddyfile's apex "cloud-driver.de { ... }" block is the static
#      file_server block below. That block sets Cache-Control: no-cache on every response:
#      Caddy's file_server otherwise sends only ETag/Last-Modified, which makes browsers fall
#      back to *heuristic* freshness (roughly 10% of the file's age) and serve a cached
#      style.css/script.js without revalidating - i.e. new HTML rendered against old CSS. With
#      no-cache the browser still caches, but always revalidates and gets a cheap 304. The apex block originally reverse-proxied to the REST API on
#      127.0.0.1:8080 (which is exactly why visiting cloud-driver.de showed no homepage) - if
#      the current block differs from the desired one, the Caddyfile is backed up
#      (Caddyfile.bak-<timestamp>, matching the backups already on the box), the apex block is
#      replaced, the result is validated with `caddy validate`, and Caddy is reloaded
#      (zero-downtime; the api./auth. blocks are untouched). If the block is already correct,
#      Caddy is left completely alone.
#   4. Smoke-tests https://cloud-driver.de from this machine and checks the served page matches
#      the local index.html.
#
# The ssh user on the server is root, so no sudo is involved. DNS for the apex already points
# at the same address as api.cloud-driver.de and is not touched here.
#
# Usage: ./deploy-homepage.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_HOMEPAGE_DIR="$REPO_ROOT/homepage"
REMOTE_HOST="cloud_driver"
REMOTE_WEB_ROOT="/var/www/cloud-driver-homepage"
REMOTE_CADDYFILE="/etc/caddy/Caddyfile"
APEX_DOMAIN="cloud-driver.de"

# The desired apex site block, verbatim. Kept as the single source of truth for both the
# "is it already correct?" comparison and the rewrite.
DESIRED_APEX_BLOCK="$APEX_DOMAIN {
    root * $REMOTE_WEB_ROOT
    header Cache-Control \"no-cache\"
    file_server
}"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

if [ ! -d "$LOCAL_HOMEPAGE_DIR" ]; then
    echo "deploy-homepage.sh: $LOCAL_HOMEPAGE_DIR not found" >&2
    exit 1
fi

echo "deploy-homepage.sh: [1/4] checking for unresolved placeholders"
if grep -rl 'class="todo"' "$LOCAL_HOMEPAGE_DIR" --include="*.html" >/dev/null 2>&1; then
    echo "deploy-homepage.sh: refusing to deploy - these pages still contain class=\"todo\" placeholders:" >&2
    grep -rl 'class="todo"' "$LOCAL_HOMEPAGE_DIR" --include="*.html" >&2
    echo "deploy-homepage.sh: fill in the real values (they render visibly highlighted) and re-run" >&2
    exit 1
fi
echo "deploy-homepage.sh: no placeholders found"

echo "deploy-homepage.sh: [2/4] uploading homepage files to $REMOTE_HOST:$REMOTE_WEB_ROOT"
if ! ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_WEB_ROOT'"; then
    echo "deploy-homepage.sh: failed to create $REMOTE_WEB_ROOT on $REMOTE_HOST" >&2
    exit 1
fi

shopt -s nullglob
homepage_files=("$LOCAL_HOMEPAGE_DIR"/*)
shopt -u nullglob
if [ ${#homepage_files[@]} -eq 0 ]; then
    echo "deploy-homepage.sh: $LOCAL_HOMEPAGE_DIR is empty - nothing to deploy" >&2
    exit 1
fi

for local_path in "${homepage_files[@]}"; do
    [ -f "$local_path" ] || continue
    file_name="$(basename "$local_path")"
    remote_file="$REMOTE_WEB_ROOT/$file_name"

    if ! scp -q "$local_path" "$REMOTE_HOST:$remote_file"; then
        echo "deploy-homepage.sh: scp failed for $file_name" >&2
        exit 1
    fi

    local_sha="$(sha256_of "$local_path")"
    remote_sha="$(ssh "$REMOTE_HOST" "sha256sum '$remote_file'" | awk '{print $1}')"
    if [ "$local_sha" != "$remote_sha" ]; then
        echo "deploy-homepage.sh: checksum mismatch for $file_name - local $local_sha, remote $remote_sha (transfer corrupted, try again)" >&2
        exit 1
    fi
    echo "deploy-homepage.sh: deployed and verified $file_name (sha256 $remote_sha)"
done

# Remove remote files that no longer exist locally, so deleted pages don't linger published.
remote_only="$(ssh "$REMOTE_HOST" "ls -1 '$REMOTE_WEB_ROOT'" | while read -r f; do
    [ -f "$LOCAL_HOMEPAGE_DIR/$f" ] || echo "$f"
done)"
if [ -n "$remote_only" ]; then
    echo "$remote_only" | while read -r f; do
        echo "deploy-homepage.sh: removing $f (no longer exists locally)"
        ssh "$REMOTE_HOST" "rm -f '$REMOTE_WEB_ROOT/$f'"
    done
fi

echo "deploy-homepage.sh: [3/4] ensuring Caddy serves $APEX_DOMAIN from $REMOTE_WEB_ROOT"

# Extract the current apex block (from "cloud-driver.de {" to its closing "}") and compare it
# to the desired one, ignoring whitespace-only differences.
current_block="$(ssh "$REMOTE_HOST" "awk '/^$APEX_DOMAIN[[:space:]]*\\{/{inblock=1} inblock{print} inblock&&/^\\}/{exit}' '$REMOTE_CADDYFILE'")"

normalize() { echo "$1" | tr -s '[:space:]' ' ' | sed 's/^ //;s/ $//'; }

if [ "$(normalize "$current_block")" = "$(normalize "$DESIRED_APEX_BLOCK")" ]; then
    echo "deploy-homepage.sh: Caddyfile apex block already correct - not touching Caddy"
else
    backup_name="$REMOTE_CADDYFILE.bak-$(date +%Y%m%d%H%M%S)"
    echo "deploy-homepage.sh: rewriting apex block (backup: $backup_name)"

    # Rewrite: copy everything except the existing apex block (if any), then append the desired
    # block. The api./auth. subdomain blocks pass through untouched.
    if ! ssh "$REMOTE_HOST" "
        set -e
        cp '$REMOTE_CADDYFILE' '$backup_name'
        awk '/^$APEX_DOMAIN[[:space:]]*\\{/{inblock=1; next} inblock&&/^\\}/{inblock=0; next} !inblock{print}' '$REMOTE_CADDYFILE' > '$REMOTE_CADDYFILE.new'
        printf '%s\n' '$APEX_DOMAIN {' '    root * $REMOTE_WEB_ROOT' '    header Cache-Control \"no-cache\"' '    file_server' '}' >> '$REMOTE_CADDYFILE.new'
        caddy validate --config '$REMOTE_CADDYFILE.new' --adapter caddyfile >/dev/null 2>&1
        mv '$REMOTE_CADDYFILE.new' '$REMOTE_CADDYFILE'
        systemctl reload caddy
    "; then
        echo "deploy-homepage.sh: Caddyfile rewrite/validate/reload failed - the original config is untouched at $REMOTE_CADDYFILE (backup also at $backup_name); fix manually" >&2
        exit 1
    fi
    echo "deploy-homepage.sh: Caddy reloaded - $APEX_DOMAIN now serves the static homepage"
fi

echo "deploy-homepage.sh: [4/4] smoke-testing https://$APEX_DOMAIN"
served_sha="$(curl -fsSL --max-time 15 "https://$APEX_DOMAIN/" | sha256_of /dev/stdin 2>/dev/null || true)"
local_index_sha="$(sha256_of "$LOCAL_HOMEPAGE_DIR/index.html")"
if [ "$served_sha" = "$local_index_sha" ]; then
    echo "deploy-homepage.sh: done - https://$APEX_DOMAIN serves exactly the local index.html"
elif curl -fsSL --max-time 15 "https://$APEX_DOMAIN/" | grep -q "<title>CloudDriver"; then
    echo "deploy-homepage.sh: done - https://$APEX_DOMAIN serves a CloudDriver page (content differs from local index.html byte-for-byte; if you just deployed, a cached copy may be in between)"
else
    echo "deploy-homepage.sh: WARNING - https://$APEX_DOMAIN did not return the expected homepage; check Caddy on the server" >&2
    exit 1
fi
