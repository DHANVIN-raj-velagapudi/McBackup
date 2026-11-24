#!/usr/bin/env bash
# Builds build/McBackup-<version>.jar (the version comes from plugin.yml) with plain javac (Java 21+). The org.bukkit stubs are only used to compile;
# they are NOT packaged: the real classes come from your Paper server.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(sed -n 's/^version: *//p' src/main/resources/plugin.yml | head -1 | tr -d '[:space:]"')
rm -rf build && mkdir -p build/stubs build/classes build/test
javac --release 21 -nowarn -d build/stubs $(find src/stubs -name '*.java')
javac --release 21 -Xlint:all -cp build/stubs -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file build/McBackup-$VERSION.jar --main-class io.mcbackup.Cli -C build/classes .
echo "built build/McBackup-$VERSION.jar"
