#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script (script-only mode), version 3.3.2
# ----------------------------------------------------------------------------
set -e

if [ -z "$MAVEN_SKIP_RC" ] && [ -f /usr/local/etc/mavenrc ]; then
  . /usr/local/etc/mavenrc
fi
if [ -z "$MAVEN_SKIP_RC" ] && [ -f /etc/mavenrc ]; then
  . /etc/mavenrc
fi
if [ -z "$MAVEN_SKIP_RC" ] && [ -f "$HOME/.mavenrc" ]; then
  . "$HOME/.mavenrc"
fi

# Find this script's real path
PRG="$0"
while [ -h "$PRG" ] ; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")"/$link"
  fi
done
PRG_DIR=$(dirname "$PRG")
MAVEN_PROJECTBASEDIR=${MAVEN_BASEDIR:-"$PRG_DIR"}
MAVEN_PROJECTBASEDIR=$(cd "$MAVEN_PROJECTBASEDIR" && pwd)

WRAPPER_PROPS="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Cannot find $WRAPPER_PROPS" >&2
  exit 1
fi

DISTRIBUTION_URL=$(grep -E '^distributionUrl=' "$WRAPPER_PROPS" | sed 's/^distributionUrl=//')
if [ -z "$DISTRIBUTION_URL" ]; then
  echo "distributionUrl missing in $WRAPPER_PROPS" >&2
  exit 1
fi

ZIP_NAME=$(basename "$DISTRIBUTION_URL" .zip)
# zip extracts to apache-maven-X.Y.Z, not apache-maven-X.Y.Z-bin
DIST_BASE=${ZIP_NAME%-bin}
INSTALL_DIR="$HOME/.m2/wrapper/dists/$ZIP_NAME"
MAVEN_HOME="$INSTALL_DIR/$DIST_BASE"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  echo "Downloading Maven from $DISTRIBUTION_URL ..." >&2
  mkdir -p "$INSTALL_DIR"
  TMP_ZIP="$INSTALL_DIR/maven.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fSL "$DISTRIBUTION_URL" -o "$TMP_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DISTRIBUTION_URL" -O "$TMP_ZIP"
  else
    echo "Need curl or wget to download Maven" >&2
    exit 1
  fi
  ( cd "$INSTALL_DIR" && unzip -q "$TMP_ZIP" )
  rm -f "$TMP_ZIP"
fi

JAVA_EXE="${JAVA_HOME:+$JAVA_HOME/bin/java}"
[ -z "$JAVA_EXE" ] && JAVA_EXE=java

exec "$MAVEN_HOME/bin/mvn" "$@"
