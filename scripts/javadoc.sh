#!/usr/bin/env bash
#
# Builds the API documentation of every published module of a json checkout as
# one site, the way the JDK documentation is built: a single javadoc run over
# all modules, so that the site has one module index, one search over every
# module, and links from one module to another and to the JDK.
#
# The published modules are those with a src/main/java/module-info.java; the
# benchmarks have none.
#
# It works on the sources of any tag, so that older versions can be documented
# with the script of today.
#
# Usage:  scripts/javadoc.sh <checkout> <output directory>
#         scripts/javadoc.sh . target/apidocs

set -euo pipefail

# The JDK whose API the documentation links to.
JDK=25
# The element-list of its documentation, committed beside this script, so that
# building the documentation does not depend on docs.oracle.com being reachable:
# with -Werror, javadoc fails when it cannot fetch it. Copied from the JDK's
# docs/api/element-list; the links still go to docs.oracle.com.
JDK_ELEMENT_LIST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/jdk-$JDK

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ $# -eq 2 ]] || die "Usage: $0 <checkout> <output directory>"
checkout=$(cd "$1" && pwd)
output=$2
[[ -f $checkout/pom.xml && -x $checkout/mvnw ]] || die "$checkout is not a json checkout."
# The project's version is the first <version> after the parent's.
version=$(awk '/<\/parent>/ { found = 1 } found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' \
    "$checkout/pom.xml")
[[ -n $version ]] || die "No version in $checkout/pom.xml."

modules=()
source_paths=()
for descriptor in "$checkout"/*/src/main/java/module-info.java; do
    [[ -e $descriptor ]] || continue
    # The module name follows the keyword, after an optional "open".
    name=$(grep -m 1 -oE '^(open[[:space:]]+)?module[[:space:]]+[A-Za-z0-9_.]+' "$descriptor" | awk '{print $NF}')
    [[ -n $name ]] || die "No module declaration in $descriptor."
    modules+=("$name")
    source_paths+=(--module-source-path "$name=${descriptor%/module-info.java}")
done
[[ ${#modules[@]} -gt 0 ]] || die "No module in $checkout."
[[ -f $JDK_ELEMENT_LIST_DIR/element-list ]] || die "No $JDK_ELEMENT_LIST_DIR/element-list."

# The third-party modules the json modules require, from each module's compile
# class path. The json modules themselves come from their sources: compiling
# lets the reactor resolve them without their version being in a repository.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
(cd "$checkout" && ./mvnw -B -q --no-transfer-progress -DskipTests compile \
    dependency:build-classpath -DincludeScope=compile \
    -Dmdep.outputFile="$work/classpath-\${project.artifactId}.txt")
# The files end without a newline: awk adds it, so that the last entry of one
# file and the first of the next stay apart.
module_path=$(awk 1 "$work"/classpath-*.txt | tr ':' '\n' \
    | grep -v -e '^$' -e '/be/imgn/json/' -e '/target/classes' | sort -u | paste -sd: -)

# The API documentation of gaia, whose types the json modules take and return,
# as gaia publishes it at the version this checkout uses: a released version, or
# the snapshot. Its element-list is fetched beforehand, retried, as javadoc would
# fetch it once and, with -Werror, fail.
GAIA_DOCS=https://imgn-dev.github.io/gaia/api
gaia_version=$(sed -nE 's|.*<gaia.version>([^<]+)</gaia.version>.*|\1|p' "$checkout/pom.xml" | head -n 1)
[[ -n $gaia_version ]] || die "No gaia.version in $checkout/pom.xml."
if [[ $gaia_version == *-SNAPSHOT ]]; then
    gaia_docs=$GAIA_DOCS/snapshot/
else
    gaia_docs=$GAIA_DOCS/$gaia_version/
fi
mkdir "$work/gaia"
curl -fsS --retry 3 -o "$work/gaia/element-list" "${gaia_docs}element-list" \
    || die "Cannot fetch the element-list of gaia's API documentation from $gaia_docs."

rm -rf "$output"
# The same checks as the javadoc jars of the parent POM: -Werror for
# failOnWarnings, -Xdoclint:all,-missing for doclint.
javadoc -d "$output" -quiet -notimestamp -Werror -Xdoclint:all,-missing \
    --release "$JDK" \
    "${source_paths[@]}" \
    --module "$(IFS=,; echo "${modules[*]}")" \
    ${module_path:+--module-path "$module_path"} \
    -linkoffline "https://docs.oracle.com/en/java/javase/$JDK/docs/api/" "$JDK_ELEMENT_LIST_DIR" \
    -linkoffline "$gaia_docs" "$work/gaia" \
    -doctitle "json $version API" \
    -windowtitle "json $version API"

# A Markdown link that javadoc cannot parse, such as one whose signature has
# unescaped array brackets, is printed as its raw text, without a warning.
# Code is left out, as `a[i][j]` is valid there.
broken=$(find "$output" -name "*.html" -exec perl -0777 -ne \
    's#<(code|pre)\b.*?</\1>##gs; print "$ARGV\n" if /\]\[|\[#/' {} +)
[[ -z $broken ]] || die "Raw Markdown links in the documentation, in:"$'\n'"$broken"
