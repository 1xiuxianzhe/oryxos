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
# Java lookup order: ORYXOS_JAVA_HOME -> JAVA_HOME -> java on PATH.
# Requires JDK 21+.
#
# Overridable:
#   ORYXOS_JAR        fat JAR path   (default: newest oryxos-boot/target/oryxos-boot-*.jar)
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
  # 取最近构建的那个包；跳过 sources / javadoc 之类的附属包
  JAR=$(ls -1t "$ROOT_DIR"/oryxos-boot/target/oryxos-boot-*.jar 2>/dev/null \
    | grep -v -e '-sources.jar' -e '-javadoc.jar' \
    | head -n 1 || true)
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

JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 \
  | awk -F'"' '/version/ {print $2; exit}' \
  | awk -F'._-' '{ if ($1 == "1") print $2; else print $1 }')

if [ -n "${JAVA_MAJOR:-}" ] && [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
  echo "[oryxos] Java $JAVA_MAJOR found, but OryxOS requires Java 21 or newer." >&2
  echo "[oryxos] Point JAVA_HOME or ORYXOS_JAVA_HOME at a JDK 21 install." >&2
  exit 1
fi

# shellcheck disable=SC2086
exec "$JAVA_BIN" ${ORYXOS_JAVA_OPTS:-} -jar "$JAR" "$@"
