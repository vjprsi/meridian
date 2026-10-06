#!/usr/bin/env bash
# Builds one container image per Meridian component (all domain services + edge gateway).
#   tools/build-images.sh [tag] [registry]        default tag: git short sha, registry: ghcr.io/meridian
# Set PUSH=1 to push. Uses infra/docker/Dockerfile (layered, non-root, read-only-root friendly).
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${1:-$(git rev-parse --short HEAD)}"
REGISTRY="${2:-ghcr.io/meridian}"
./gradlew bootJar --console=plain -q
for jar in services/*/build/libs/*.jar platform/edge-gateway/build/libs/*.jar; do
  case "$jar" in *-plain.jar) continue;; esac
  name="$(basename "$(dirname "$(dirname "$(dirname "$jar")")")")"
  docker build -q -f infra/docker/Dockerfile --build-arg JAR="$jar" -t "$REGISTRY/$name:$TAG" . >/dev/null
  echo "built $REGISTRY/$name:$TAG"
  if [ "${PUSH:-0}" = "1" ]; then docker push -q "$REGISTRY/$name:$TAG"; fi
done
