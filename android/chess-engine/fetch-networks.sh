#!/bin/sh
# Downloads the two Stockfish neural networks the engine needs, into the module's
# assets so they ship with the app and the instrumented tests.
#
# They are ~167 MB together and are NOT committed: build inputs with a canonical
# source, their exact names pinned by the vendored engine (evaluate.h). Run once
# after cloning; needs nothing but curl. (Reckless's net is out of scope for v1.)
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
EVAL="$ROOT/../../ios/Vendor/Stockfish/src/evaluate.h"
DIR="$ROOT/src/main/assets/networks"
mkdir -p "$DIR"

BIG=$(grep -o 'nn-[a-f0-9]*\.nnue' "$EVAL" | head -1)
SMALL=$(grep -o 'nn-[a-f0-9]*\.nnue' "$EVAL" | tail -1)

for net in "$BIG" "$SMALL"; do
    if [ -f "$DIR/$net" ]; then
        echo "already present: $net"
        continue
    fi
    echo "downloading $net ..."
    curl -sL --fail "https://tests.stockfishchess.org/api/nn/$net" -o "$DIR/$net"
done

ls -lh "$DIR" | tail -n +2 | awk '{printf "  %-26s %s\n", $9, $5}'
