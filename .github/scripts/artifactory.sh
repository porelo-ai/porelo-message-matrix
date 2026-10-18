# Artifactory's REST interface over curl, for the jobs that publish: nothing is downloaded to speak to it, so
# nothing runs beside the token that a pin or a checksum would have to vouch for.
#
# Sourced, not run. Needs JF_URL (the platform URL, without /artifactory), and JF_ACCESS_TOKEN for art_login.
# The token goes into a curl config file of mode 600 under RUNNER_TEMP, never onto a command line: neither
# 'set -x' nor /proc/<pid>/cmdline shows it.

art_config="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/artifactory.curl"

# Writes the token into the config file every later call reads.
art_login() {
    case $- in *x*) art_trace=1; set +x ;; *) art_trace=0 ;; esac
    if [ -z "${JF_ACCESS_TOKEN:-}" ]; then
        echo "::error::secret JF_ACCESS_TOKEN is not set"
        [ "$art_trace" = 0 ] || set -x
        return 1
    fi
    (umask 077 && printf 'header = "Authorization: Bearer %s"\n' "$JF_ACCESS_TOKEN" > "$art_config")
    [ "$art_trace" = 0 ] || set -x
}

# art_put FILE TARGET [PROPERTIES]: uploads FILE to TARGET, a path in a repository; a TARGET ending in '/' takes
# the file's own name, as 'jf rt upload --flat=true' did. PROPERTIES are 'key=value' joined by ';', set with the
# upload - a Debian package needs deb.distribution, deb.component and deb.architecture, or it is stored and never
# indexed. Both checksums travel with it, and Artifactory refuses an upload whose bytes do not match them.
art_put() {
    art_file="$1" art_target="$2" art_props="${3:-}"
    [ -f "$art_file" ] || { echo "::error::no file '$art_file' to upload"; return 1; }
    case "$art_target" in */) art_target="$art_target$(basename "$art_file")" ;; esac
    curl -fsS -K "$art_config" -o /dev/null -T "$art_file" \
        -H "X-Checksum-Sha1: $(sha1sum "$art_file" | cut -d' ' -f1)" \
        -H "X-Checksum-Sha256: $(sha256sum "$art_file" | cut -d' ' -f1)" \
        "${JF_URL%/}/artifactory/${art_target}${art_props:+;$art_props}"
    echo "  put   $art_target"
}

# art_api METHOD PATH: a call to the REST interface, PATH starting with '/'; prints the answer.
art_api() {
    curl -fsS -K "$art_config" -X "$1" "${JF_URL%/}/artifactory$2"
}
