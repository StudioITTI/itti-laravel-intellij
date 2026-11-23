#!/usr/bin/env bash
# Writes build/distributions/updatePlugins.xml, the custom plugin repository PhpStorm reads updates from (see README).
# Run after `./gradlew buildPlugin`; the argument is the release tag the zip gets attached to.
set -euo pipefail

TAG="${1:?usage: update-plugins-xml.sh <release tag>}"
REPO_URL=$(sed -n 's/^pluginRepositoryUrl *= *//p' gradle.properties)
XML=build/tmp/patchPluginXml/plugin.xml

element() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$XML" | head -n 1; }
attribute() { sed -n "s/.*$1=\"\([^\"]*\)\".*/\1/p" "$XML" | head -n 1; }

# the zip of this version, not one an earlier build left behind
ZIP=$(basename "$(ls build/distributions/*-"$(element version)".zip | head -n 1)")

IDEA_VERSION="<idea-version since-build=\"$(attribute since-build)\"/>"
UNTIL=$(attribute until-build)
[ -n "$UNTIL" ] && IDEA_VERSION="<idea-version since-build=\"$(attribute since-build)\" until-build=\"$UNTIL\"/>"

cat > build/distributions/updatePlugins.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="$(element id)" url="$REPO_URL/releases/download/$TAG/$ZIP" version="$(element version)">
    $IDEA_VERSION
    <name>$(element name)</name>
    <vendor>$(element vendor)</vendor>
  </plugin>
</plugins>
EOF
cat build/distributions/updatePlugins.xml
