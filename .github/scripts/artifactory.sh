# Artifactory's REST interface over curl, for the jobs that publish: nothing is downloaded to speak to it, so
# nothing runs beside the token that a pin or a checksum would have to vouch for.
#
# Sourced, not run. Needs JF_URL (the platform URL, without /artifactory), and JF_ACCESS_TOKEN for art_login.
# The token reaches each curl call as its config on standard input ('-K -'), never in a file and never on a
# command line: neither 'set -x' nor /proc/<pid>/cmdline shows it, and nothing is left on the runner's disk.

# Refuses to go on without the token, before any call is made.
art_login() {
    # ':+set' so that a trace shows the word, not the token.
    if [ -z "${JF_ACCESS_TOKEN:+set}" ]; then
        echo "::error::secret JF_ACCESS_TOKEN is not set"
        return 1
    fi
}

# art_curl ARGS...: curl with the token's header on standard input. Tracing is off while the token is printed.
art_curl() {
    case $- in *x*) art_trace=1; set +x ;; *) art_trace=0 ;; esac
    printf 'header = "Authorization: Bearer %s"\n' "$JF_ACCESS_TOKEN" | curl -fsS -K - "$@"
    art_status=$?
    [ "$art_trace" = 0 ] || set -x
    return "$art_status"
}

# art_put FILE TARGET [PROPERTIES]: uploads FILE to TARGET, a path in a repository; a TARGET ending in '/' takes
# the file's own name, as 'jf rt upload --flat=true' did. PROPERTIES are 'key=value' joined by ';', set with the
# upload - a Debian package needs deb.distribution, deb.component and deb.architecture, or it is stored and never
# indexed. Both checksums travel with it, and Artifactory refuses an upload whose bytes do not match them.
art_put() {
    art_file="$1" art_target="$2" art_props="${3:-}"
    [ -f "$art_file" ] || { echo "::error::no file '$art_file' to upload"; return 1; }
    case "$art_target" in */) art_target="$art_target$(basename "$art_file")" ;; esac
    art_curl -o /dev/null -T "$art_file" \
        -H "X-Checksum-Sha1: $(sha1sum "$art_file" | cut -d' ' -f1)" \
        -H "X-Checksum-Sha256: $(sha256sum "$art_file" | cut -d' ' -f1)" \
        "${JF_URL%/}/artifactory/${art_target}${art_props:+;$art_props}"
    echo "  put   $art_target"
}

# art_api METHOD PATH: a call to the REST interface, PATH starting with '/'; prints the answer.
art_api() {
    art_curl -X "$1" "${JF_URL%/}/artifactory$2"
}
