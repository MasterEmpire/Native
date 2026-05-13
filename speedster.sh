#!/bin/bash
set -e

echo "🚀 Starting Phase 2 & 3: The Forge and The Crunch"

WORKDIR="dynamic_build"
CLASSES_DIR="$WORKDIR/classes"
DEX_DIR="$WORKDIR/dex"
KOTLIN_DIR="$WORKDIR/kotlinc"
LIBS_DIR="app/build/harvested_libs"

mkdir -p $CLASSES_DIR
mkdir -p $DEX_DIR

if [ ! -d "$KOTLIN_DIR" ]; then
    echo "⬇️ Downloading Kotlin Compiler 1.9.24..."
    wget -q https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip -O $WORKDIR/kotlin.zip
    unzip -q $WORKDIR/kotlin.zip -d $WORKDIR/
fi

if [ ! -f "$WORKDIR/compose-compiler.jar" ]; then
    echo "⬇️ Downloading Compose Compiler 1.5.14..."
    wget -q https://dl.google.com/dl/android/maven2/androidx/compose/compiler/compiler-hosted/1.5.14/compiler-hosted-1.5.14.jar -O $WORKDIR/compose-compiler.jar
fi

echo "🔗 Building Classpath..."
echo "📦 Inventorying the Armory..."
CP=$(find $LIBS_DIR -name "*.jar" | tr '\n' ':')
if [ -z "$CP" ]; then
    echo "❌ ERROR: Armory is empty! harvestDeps failed to gather JARs."
    exit 1
fi
ANDROID_SDK_ROOT="/usr/local/lib/android/sdk"
CP="$CP:${ANDROID_SDK_ROOT}/platforms/android-34/android.jar"

echo "⚔️ FORGING (Kotlin -> Bytecode)..."
# Compile the dynamic source ALONG WITH the interface contract
$WORKDIR/kotlinc/bin/kotlinc \
    dynamic_src/ \
    app/src/main/java/com/example/myandroid/dynamic/DynamicEntry.kt \
    -cp "$CP" \
    -Xplugin=$WORKDIR/compose-compiler.jar \
    -d $CLASSES_DIR

echo "🧹 CLEANING DUPLICATES..."
# Remove the interface class so the payload doesn't carry its own copy.
# If we don't do this, DexClassLoader throws a ClassCastException.
find $CLASSES_DIR -name "DynamicEntry*" -delete

echo "🔩 CRUNCHING (Bytecode -> Dex)..."
BUILD_TOOLS_DIR=$(ls -d ${ANDROID_SDK_ROOT}/build-tools/34.* | head -1)

$BUILD_TOOLS_DIR/d8 \
    --output $DEX_DIR/ \
    --lib ${ANDROID_SDK_ROOT}/platforms/android-34/android.jar \
    $(find $CLASSES_DIR -name "*.class")

echo "📦 PACKAGING BUNDLE..."
BUNDLE_DIR="$WORKDIR/bundle"
mkdir -p $BUNDLE_DIR/res
cp $DEX_DIR/classes.dex $BUNDLE_DIR/

# If you have images, put them in dynamic_res/ before running this script
if [ -d "dynamic_res" ]; then
    cp -r dynamic_res/* $BUNDLE_DIR/res/
fi

cd $BUNDLE_DIR && zip -r ../../bundle.zip . && cd ../..

echo "✅ BUNDLE READY: bundle.zip"
