#!/usr/bin/env bash
# Which images a change touches (D15), as a JSON array for images.yml's build matrix:
#   [{"module":"checkout-payment-service","kind":"service"}, ...]
#
#   scripts/affected-images.sh <base-sha> <head-sha>     # the commits a push brought
#   scripts/affected-images.sh --all                     # every image
#
# An image is a module with a Dockerfile. It is affected when a changed file lies in the module,
# in any module it depends on (project(":x") in build.gradle.kts, followed transitively), or in
# the build itself (root build files, gradle/, the wrapper). The dependencies are read from the
# build files every run rather than kept in a map: a hand-kept map is how a stale image runs
# green (retro, 6a55391). A base that git does not know (a first push, a force push) means
# every image.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

modules=$(sed -n 's/^ *"\([a-z0-9-]*\)",\{0,1\}$/\1/p' settings.gradle.kts)
images=$(for m in $modules; do [[ -f "$m/Dockerfile" ]] && echo "$m"; done)

# A Lambda image is built differently (no attestations: Lambda takes a single manifest, D15);
# the module names say which is which (D25).
kind_of() { [[ "$1" == *-lambda ]] && echo lambda || echo service; }

# The module and every project it depends on, transitively.
closure() {
  local todo=("$1") seen=() m d
  while ((${#todo[@]})); do
    m=${todo[0]}; todo=("${todo[@]:1}")
    [[ " ${seen[*]} " == *" $m "* ]] && continue
    seen+=("$m")
    while read -r d; do
      [[ -n "$d" ]] && todo+=("$d")
    done < <(grep -o 'project(":[a-z0-9-]*")' "$m/build.gradle.kts" 2>/dev/null | sed 's/project(":\(.*\)")/\1/')
  done
  echo "${seen[@]}"
}

emit() { # module...
  local first=1 m
  printf '['
  for m in "$@"; do
    ((first)) || printf ','
    first=0
    printf '{"module":"%s","kind":"%s"}' "$m" "$(kind_of "$m")"
  done
  printf ']\n'
}

if [[ "${1:-}" == --all ]]; then
  # shellcheck disable=SC2086  # one word per module
  emit $images
  exit 0
fi

BASE=${1:?base sha, or --all}
HEAD=${2:?head sha}
if ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then
  # shellcheck disable=SC2086
  emit $images
  exit 0
fi

changed=$(git diff --name-only "$BASE" "$HEAD")
build_changed=$(grep -E '^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew|gradle/)' <<<"$changed" || true)

affected=()
for img in $images; do
  if [[ -n "$build_changed" ]]; then
    affected+=("$img")
    continue
  fi
  for m in $(closure "$img"); do
    if grep -q "^$m/" <<<"$changed"; then
      affected+=("$img")
      break
    fi
  done
done
emit "${affected[@]}"
