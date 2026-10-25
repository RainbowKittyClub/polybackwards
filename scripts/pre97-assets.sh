#!/usr/bin/env bash
# Regenerates src/main/resources/polybackwards/pre97_assets.txt from the Loom-cached client jars:
# every vanilla texture, model and item definition the 26.3 jar holds that some older jar lacks.
#
#   scripts/pre97-assets.sh
#
# Each jar must be in Loom's cache (any Loom build or decompile for that version puts it there);
# LOOM_CACHE overrides where that cache is.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
LOOM_CACHE="${LOOM_CACHE:-$HOME/.gradle/caches/fabric-loom}"
NEWEST=26.3
OLDER=(26.1 26.1.1 26.1.2 26.2)
OUT=src/main/resources/polybackwards/pre97_assets.txt

# The jar's textures, models and item definitions, as sorted paths under assets/minecraft/.
assets() {
    local jar="$LOOM_CACHE/$1/minecraft-client.jar"
    [[ -f "$jar" ]] || { echo "no $jar; build or decompile $1 with Loom first" >&2; exit 1; }
    unzip -Z1 "$jar" \
        | grep -E '^assets/minecraft/(textures/.+\.png|models/.+\.json|items/.+\.json)$' \
        | sed 's#^assets/minecraft/##' \
        | LC_ALL=C sort
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
assets "${OLDER[0]}" > "$work/common"
for version in "${OLDER[@]:1}"; do
    assets "$version" | LC_ALL=C comm -12 "$work/common" - > "$work/next"
    mv "$work/next" "$work/common"
done

# Built aside and moved in, so a missing 26.3 jar leaves the shipped list as it was.
{
    echo "# Vanilla assets $NEWEST has that a ${OLDER[*]} client lacks, as paths under"
    echo "# assets/minecraft/. Pre97Overlay ships each one the pack reaches. Regenerate with"
    echo "# scripts/pre97-assets.sh."
    assets "$NEWEST" | LC_ALL=C comm -23 - "$work/common"
} > "$work/out"
mv "$work/out" "$OUT"
echo "$(grep -vc '^#' "$OUT") entries in $OUT"
