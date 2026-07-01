#!/usr/bin/env bash
# Dev-only entrypoint for the Dockerfile `dev-run` stage.
#
# Runs two cooperating processes against the bind-mounted /app/src so that
# editing a .java (or resource) file on the host hot-reloads the running app:
#
#   1. A background mtime-poll loop that recompiles src -> target/classes when
#      a source file changes, then touches the DevTools trigger file. We poll
#      mtimes (find -newer) rather than using inotify/entr because inotify
#      events do not cross the macOS Docker Desktop bind mount, but file mtimes
#      do. The poll is cheap when idle and only pays the mvn cost on a real edit.
#
#   2. The app itself via `mvn spring-boot:run`. With spring-boot-devtools on
#      the classpath, DevTools watches target/classes and does its fast in-JVM
#      context restart when the trigger file appears. Fork stays enabled (the
#      default) — disabling it would disable DevTools.
set -euo pipefail

marker=/tmp/.last-build
trigger=target/classes/.reloadtrigger

# Seed the marker at startup so the first poll only fires on a genuine
# post-startup edit, not on source whose host mtime predates the container.
touch "$marker"

watch_and_compile() {
  while true; do
    if [ -n "$(find src -type f \( -name '*.java' -o -name '*.yml' -o -name '*.yaml' \
         -o -name '*.properties' -o -name '*.xml' \) -newer "$marker" 2>/dev/null)" ]; then
      # Stamp the marker before compiling so edits made mid-compile are caught
      # on the next pass rather than being lost.
      touch "$marker"
      # A compile failure (mid-edit syntax error) must not kill the loop, so
      # guard it in the condition — set -e does not fire on if-conditions.
      if mvn -o -q compile process-classes; then
        touch "$trigger"
      fi
    fi
    sleep 2
  done
}

watch_and_compile &

exec mvn spring-boot:run -Dspring-boot.run.profiles=local
