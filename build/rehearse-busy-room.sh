#!/bin/sh
# Rehearses a project's room on a host in one call, as the person <login>: the room rehearsal of this repository's
# tests (RoomRehearsal), which ships in no package. It forwards the host's Matrix gate socket as <login>, finds the
# project's room and its running tasks itself, runs the scenarios with each task's record read by `porelo talk log`
# on the host, and closes the forward again.
# Its exit is the rehearsal's: 0 every scenario held, 65 one did not or the room holds fewer than two running tasks,
# 75 the host or its homeserver could not be reached (the forward too), 64 a wrong call; 70 the build failed.
# ssh is the caller's: its config, its agent and its known hosts decide how <login>@<host> is reached.
# Each further <person> is the login of another person of the project on the host, whose account and helpers in the
# room are then no stranger's.
# REHEARSE_CLASSPATH, when set, is the test classpath to run, else it is built from this checkout with Maven.
# usage: build/rehearse-busy-room.sh <login on the host> <host> <project> [<person>...]
set -u
usage() {
    echo "usage: $0 <login> <host> <project> [<person>...]: $1" >&2
    exit 64
}
[ $# -ge 3 ] || usage "three arguments or more"
login=$1
host=$2
project=$3
shift 3
# grep answers for each line, so a value with a second line would pass on its first.
fits() {
    case $2 in *'
'*) return 1 ;; esac
    printf '%s\n' "$2" | grep -Eqx "$1"
}
fits '[a-z][a-z0-9-]{0,31}' "$login" || usage "no login: $login"
fits '[A-Za-z0-9][A-Za-z0-9.:-]*' "$host" || usage "no host: $host"
fits '[a-z0-9][a-z0-9._=-]{0,120}' "$project" || usage "no project: $project"
# The further persons, each as the rehearsal takes it, in place of the arguments.
for person in "$@"; do
    fits '[a-z][a-z0-9-]{0,31}' "$person" || usage "no person's login: $person"
    set -- "$@" --person-login "$person"
    shift
done
wait=${REHEARSE_FORWARD_WAIT:-30}
fits '[0-9]{1,3}' "$wait" || usage "REHEARSE_FORWARD_WAIT is seconds: $wait"

cd "$(dirname "$0")/.." || exit 70
# A Unix socket's path holds about 100 bytes, so the forward's lies under a short directory, not a long TMPDIR.
work=$(mktemp -d -p "${XDG_RUNTIME_DIR:-/tmp}" rbr.XXXXXX) || exit 70
forward=
# shellcheck disable=SC2329 # run by the EXIT trap
end() {
    [ -z "$forward" ] || kill "$forward" 2>/dev/null
    [ -z "$forward" ] || wait "$forward" 2>/dev/null
    rm -rf "$work"
}
trap end EXIT
trap 'exit 75' INT TERM HUP

if [ -n "${REHEARSE_CLASSPATH:-}" ]; then
    classpath=$REHEARSE_CLASSPATH
else
    ./mvnw -B -q -ntp -s settings.xml test-compile dependency:build-classpath -Dmdep.includeScope=test \
        "-Dmdep.outputFile=$work/classpath" >&2 || exit 70
    classpath="target/test-classes:target/classes:$(cat "$work/classpath")"
fi

socket=$work/client.sock
ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ForwardAgent=no \
    -L "$socket:/run/porelo-matrix/client.sock" "$login@$host" </dev/null &
forward=$!
waited=0
until [ -S "$socket" ]; do
    if ! kill -0 "$forward" 2>/dev/null || [ "$waited" -ge "$wait" ]; then
        echo "the forward to $host did not come up in ${wait} s" >&2
        exit 75
    fi
    sleep 1
    waited=$((waited + 1))
done

"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$classpath" org.fuin.porelo.message.matrix.RoomRehearsal \
    --socket "$socket" --project "$project" "$@" --talk-log ssh -o BatchMode=yes "$login@$host" porelo talk log
exit $?
