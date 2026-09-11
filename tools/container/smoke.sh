#!/bin/sh
set -eu

imageName=${1:-lookahead-content-api:local}

# Docker gives DOCKER_CONTEXT precedence over DOCKER_HOST. Reject remote engines
# before starting a container; this helper owns only a local throwaway instance.
if [ -n "${DOCKER_CONTEXT:-}" ]; then
  endpoint=$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')
elif [ -n "${DOCKER_HOST:-}" ]; then
  endpoint=$DOCKER_HOST
else
  contextName=$(docker context show)
  endpoint=$(docker context inspect "$contextName" --format '{{.Endpoints.docker.Host}}')
fi
case "$endpoint" in
  unix://*) ;;
  *) printf '%s\n' 'Container smoke requires a local Unix-socket Docker engine.' >&2; exit 1 ;;
esac

containerId=$(docker run --detach --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --cap-drop ALL --security-opt no-new-privileges:true --init \
  --stop-timeout 30 "$imageName")
trap 'docker rm --force "$containerId" >/dev/null' EXIT HUP INT TERM

attempt=0
while [ "$attempt" -lt 30 ]; do
  healthStatus=$(docker inspect --format '{{.State.Health.Status}}' "$containerId")
  case "$healthStatus" in
    healthy)
      test "$(docker exec "$containerId" id -u)" = 10001
      docker exec "$containerId" java -cp /app/healthcheck Healthcheck
      printf '%s\n' 'Container readiness and non-root runtime passed.'
      exit 0
      ;;
    unhealthy) break ;;
  esac
  attempt=$((attempt + 1))
  sleep 2
done

docker logs "$containerId"
printf '%s\n' 'Container did not become ready within 60 seconds.' >&2
exit 1
