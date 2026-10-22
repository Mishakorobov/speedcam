#!/usr/bin/env bash
# =============================================================================
#  update-osm.sh — refresh the OpenStreetMap camera database on Linux / macOS
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

OSM_DIR="${OSM_DIR:-$PROJECT_ROOT/osm}"
DATA_DIR="${DATA_DIR:-$PROJECT_ROOT/data}"
DB="${DB:-$DATA_DIR/russia.db}"
MANIFEST="${MANIFEST:-$DATA_DIR/manifest.json}"
SPEEDCAM_FILE="${SPEEDCAM_FILE:-}"
CONTRACAM_FILE="${CONTRACAM_FILE:-}"
DOWNLOAD_URL="${DOWNLOAD_URL:-}"
VERSION="${VERSION:-}"
FORCE="${FORCE:-false}"

BASE_URL="https://download.geofabrik.de/russia"

mkdir -p "$OSM_DIR" "$DATA_DIR"

DISTRICTS=(
    "kaliningrad:28"
    "crimean-fed-district:39"
    "north-caucasus-fed-district:123"
    "south-fed-district:301"
    "far-eastern-fed-district:373"
    "ural-fed-district:378"
    "siberian-fed-district:560"
    "northwestern-fed-district:625"
    "volga-fed-district:740"
    "central-fed-district:840"
)

log() {
    echo "    $*"
}

calc_md5() {
    local file="$1"
    if command -v md5sum >/dev/null 2>&1; then
        md5sum "$file" | awk '{print tolower($1)}'
    elif command -v md5 >/dev/null 2>&1; then
        md5 -q "$file" | tr '[:upper:]' '[:lower:]'
    else
        python3 -c "import hashlib; print(hashlib.md5(open('$file','rb').read()).hexdigest())"
    fi
}

echo "SpeedCam - OpenStreetMap & SpeedCam Update Pipeline"
echo "============================================================"
echo "  Project:  $PROJECT_ROOT"
echo "  Extracts: $OSM_DIR"
echo "  Database: $DB"
echo "  Manifest: $MANIFEST"
echo ""

manifest_path="$OSM_DIR/imported.txt"
touch "$manifest_path"

changed_count=0

for entry in "${DISTRICTS[@]}"; do
    IFS=":" read -r name size_mb <<< "$entry"
    file="$name-latest.osm.pbf"
    target="$OSM_DIR/$file"
    md5_url="$BASE_URL/$file.md5"

    log "Checking $name..."
    remote_md5=$(curl -sS -L --retry 3 --connect-timeout 20 --max-time 60 "$md5_url" 2>/dev/null | awk '{print tolower($1)}' || true)

    if [[ -z "$remote_md5" ]]; then
        log "WARNING: Could not fetch MD5 for $name. Keeping local copy if available."
        continue
    fi

    local_md5=""
    if [[ -f "$target" ]]; then
        local_md5=$(calc_md5 "$target")
    fi

    if [[ "$FORCE" != "true" && "$local_md5" == "$remote_md5" ]]; then
        log "$name is up-to-date ($remote_md5)"
        continue
    fi

    log "Fetching $name ($size_mb MB estimated)..."
    success=false
    for attempt in 1 2 3; do
        if curl -sS -L --fail --retry 3 --retry-delay 5 --speed-time 120 --speed-limit 512 -o "$target.tmp" "$BASE_URL/$file"; then
            tmp_md5=$(calc_md5 "$target.tmp")
            if [[ "$tmp_md5" == "$remote_md5" ]]; then
                mv "$target.tmp" "$target"
                success=true
                log "$name successfully downloaded and verified ($tmp_md5)"
                changed_count=$((changed_count + 1))
                break
            else
                log "Checksum mismatch for $name (attempt $attempt/3). Retrying..."
                rm -f "$target.tmp"
            fi
        else
            log "Download failed for $name (attempt $attempt/3). Retrying..."
            rm -f "$target.tmp"
        fi
        sleep 3
    done

    if [[ "$success" != "true" ]]; then
        log "ERROR: Failed to update $name after 3 attempts."
    fi
done

echo ""
echo "[2/3] Building unified camera database & manifest..."

gradle_args=("-p" "$PROJECT_ROOT" ":tools:import:run" "-PosmDir=$OSM_DIR" "-Poutput=$DB" "-Pmanifest=$MANIFEST")

if [[ -n "$SPEEDCAM_FILE" && -f "$SPEEDCAM_FILE" ]]; then
    gradle_args+=("-PspeedcamFile=$SPEEDCAM_FILE")
fi

if [[ -n "$CONTRACAM_FILE" && -f "$CONTRACAM_FILE" ]]; then
    gradle_args+=("-PcontracamFile=$CONTRACAM_FILE")
fi

if [[ -n "$DOWNLOAD_URL" ]]; then
    gradle_args+=("-PdownloadUrl=$DOWNLOAD_URL")
fi

if [[ -n "$VERSION" ]]; then
    gradle_args+=("-Pversion=$VERSION")
fi

"$PROJECT_ROOT/gradlew" "${gradle_args[@]}"

echo ""
echo "[3/3] Verifying database integrity and schema hash..."
"$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" ":tools:import:verifyDatabase" "-Pdb=$DB" "-Pmanifest=$MANIFEST"

echo ""
echo "Pipeline finished successfully."
