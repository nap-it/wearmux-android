#!/usr/bin/env bash
# Descarrega o modelo YAMNet TFLite + class map para o `assets/` da app.
# YAMNet: classificador de áudio com 521 classes (AudioSet ontology).
# Usado pelo UC4.3 (sons ambientes → vibração no watch).
set -euo pipefail

ASSETS_DIR="$(cd "$(dirname "$0")/../app/src/main/assets" && pwd)"

echo "→ Assets dir: $ASSETS_DIR"

if [ -f "$ASSETS_DIR/yamnet.tflite" ] && [ -f "$ASSETS_DIR/yamnet_class_map.csv" ]; then
    echo "✓ Already present — nothing to do. To force a re-download, delete first:"
    echo "    rm $ASSETS_DIR/yamnet.tflite $ASSETS_DIR/yamnet_class_map.csv"
    exit 0
fi

# YAMNet "classification" — saída [N, 521]. ~3.7 MB.
YAMNET_TFLITE_URL="https://www.kaggle.com/api/v1/models/google/yamnet/tfLite/classification/1/download"
YAMNET_LABELS_URL="https://storage.googleapis.com/audioset/yamnet/yamnet_class_map.csv"

echo "→ Downloading yamnet_class_map.csv …"
curl -L -f -o "$ASSETS_DIR/yamnet_class_map.csv" "$YAMNET_LABELS_URL"

echo "→ Downloading yamnet.tflite (~3.7 MB) …"
# O Kaggle redirect dá um .tar.gz com o .tflite dentro. Resolvemos:
TMP=$(mktemp -d)
trap "rm -rf $TMP" EXIT
curl -L -f -o "$TMP/yamnet.tar.gz" "$YAMNET_TFLITE_URL" || {
    echo "❌ Kaggle download failed. Alternatives:"
    echo "   1. Log in at https://www.kaggle.com/models/google/yamnet/tfLite"
    echo "      and download it manually to $ASSETS_DIR/yamnet.tflite"
    echo "   2. Or use the direct Coral link:"
    echo "      https://github.com/google-coral/test_data/raw/master/yamnet_classification.tflite"
    exit 1
}
tar -xzf "$TMP/yamnet.tar.gz" -C "$TMP"
# Encontra o .tflite dentro do tar
TFLITE_FILE=$(find "$TMP" -name "*.tflite" | head -1)
if [ -z "$TFLITE_FILE" ]; then
    echo "❌ No .tflite found in the archive. Check manually in $TMP"
    exit 1
fi
cp "$TFLITE_FILE" "$ASSETS_DIR/yamnet.tflite"

echo "✓ Done:"
ls -lh "$ASSETS_DIR/yamnet.tflite" "$ASSETS_DIR/yamnet_class_map.csv"
echo ""
echo "Next step: rebuild + reinstall the app."
echo "    ./gradlew :app:installDebug"
