#!/bin/bash
set -e

echo "\uD83D\uDE80 Starting Phase 2 & 3: The Forge and The Crunch"

# Directories
WORKDIR="dynamic_build"
CLASSES_DIR="$WORKDIR/classes"
DEX_DIR="$WORKDIR/dex"
KOTLIN_DIR="$WORKDIR/kotlinc"
LIBS_DIR="app/build/harvested_libs"

mkdir -p $CLASSES_DIR
mkdir -p $DEX_DIR

# 1. Download Tools if missing
if[ ! -d "$KOTLIN_DIR" ]; then
    echo "\u2B07\uFE0F Downloading Kotlin Compiler 1.9.24..."
    wget -q https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip -O $WORKDIR/kotlin.zip
    unzip -q $WORKDIR/kotlin.zip -d $WORKDIR/
fi

if [ ! -f "$WORKDIR/compose-compiler.jar" ]; then
    echo "\u2B07\uFE0F Downloading Compose Compiler 1.5.14..."
    wget -q https://repo1.maven.org/maven2/androidx/compose/compiler/compiler/1.5.14/compiler-1.5.14.jar -O $WORKDIR/compose-compiler.jar
fi

# 2. Build Giga-Classpath
echo "\uD83D\uDD17 Building Classpath from Armory..."
if [ ! -d "$LIBS_DIR" ]; then
    echo "\u274C ERROR: $LIBS_DIR not found. Run './gradlew app:harvestDeps' first!"
    exit 1
fi

CP=$(find $LIBS_DIR -name "*.jar" | tr '\n' ':')

if [ -z "$ANDROID_SDK_ROOT" ]; then
    ANDROID_SDK_ROOT="/usr/local/lib/android/sdk"
fi
CP="$CP:${ANDROID_SDK_ROOT}/platforms/android-34/android.jar"

# 3. PHASE 2: The Forge (Compile to .class)
echo "\u2694\uFE0F FORGING (Kotlin -> Bytecode)..."
$KOTLIN_DIR/bin/kotlinc dynamic_src/ \
    -cp "$CP" \
    -Xplugin=$WORKDIR/compose-compiler.jar \
    -d $CLASSES_DIR

# 4. PHASE 3: The Crunch (Dexing)
echo "\uD83D\uDDDC\uFE0F CRUNCHING (Bytecode -> Dex)..."
BUILD_TOOLS_DIR=$(ls -d ${ANDROID_SDK_ROOT}/build-tools/34.* | head -1)

$BUILD_TOOLS_DIR/d8 \
    --output $DEX_DIR/ \
    --lib ${ANDROID_SDK_ROOT}/platforms/android-34/android.jar \
    $(find $CLASSES_DIR -name "*.class")

echo "\u2705 PAYLOAD READY: $DEX_DIR/classes.dex"
