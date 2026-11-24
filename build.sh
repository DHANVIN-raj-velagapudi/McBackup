#!/usr/bin/env bash
# Builds build/McBackup-1.0.0.jar with plain javac (Java 21+). The org.bukkit stubs are only used to compile;
# they are NOT packaged: the real classes come from your Paper server.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/stubs build/classes build/test
javac --release 21 -nowarn -d build/stubs $(find src/stubs -name '*.java')
javac --release 21 -Xlint:all -cp build/stubs -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file build/McBackup-1.0.0.jar --main-class io.mcbackup.Cli -C build/classes .
echo "built build/McBackup-1.0.0.jar"
