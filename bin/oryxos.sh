#!/usr/bin/env sh
# ============================================================
# OryxOS launcher (Linux / macOS / WSL)
#
# Usage:
#   bin/oryxos.sh init
#   bin/oryxos.sh status
#   bin/oryxos.sh chat --profile default
#   bin/oryxos.sh serve --port 8080
#
# Overridable:
#   ORYXOS_JAVA_HOME  JDK 21 home (default: JAVA_HOME, then java on PATH)
#   ORYXOS_JAR        fat JAR path   (default: oryxos-boot/target/oryxos-boot-*.jar)
#   ORYXOS_JAVA_OPTS  extra JVM options
# ============================================================
set -eu

BIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$BIN_DIR/.." && pwd)

if [ -n "${ORYXOS_JAVA_HOME:-}" ]; then
  JAVA_BIN="$ORYXOS_JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN=java
fi

if [ -n "${ORYXOS_JAR:-}" ]; then
  JAR="$ORYXOS_JAR"
else
  JAR=$(ls -1 "$ROOT_DIR"/oryxos-boot/target/oryxos-boot-*.jar 2>/dev/null | head -n 1 || true)
fi

if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
  echo "[oryxos] fat JAR not found under $ROOT_DIR/oryxos-boot/target/" >&2
  echo "[oryxos] Build it first: mvn -DskipTests package" >&2
  echo "[oryxos] Or point ORYXOS_JAR at an existing oryxos-boot-*.jar" >&2
  exit 1
fi

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "[oryxos] java not found: $JAVA_BIN" >&2
  echo "[oryxos] Install JDK 21 and set JAVA_HOME, or set ORYXOS_JAVA_HOME." >&2
  exit 1
fi

# shellcheck disable=SC2086
exec "$JAVA_BIN" ${ORYXOS_JAVA_OPTS:-} -jar "$JAR" "$@"
