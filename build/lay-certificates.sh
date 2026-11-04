#!/bin/sh
# Lays the certificates a person signed for local agents into the account of the person's tasks on a host, where a
# receiving task finds a key's certificate by its fingerprint. Run on the development machine when a host comes up, and
# again to renew. It signs nothing and takes no private key: the person's signature stays a deliberate step. Every file
# is checked before anything leaves, so one bad file lays none.
# usage: build/lay-certificates.sh <login on the host> <host> <project> <certificate file>...
set -u
LC_ALL=C
TZ=UTC
export LC_ALL TZ

usage() {
    echo "usage: $0 <login> <host> <project> <certificate file>...: $1" >&2
    exit 64
}
refuse() { # <file> <why>
    echo "REFUSED: $1: $2; nothing was laid" >&2
    exit 65
}
matches() { # <extended regular expression> <value>, which grep would read line by line
    [ "$(printf '%s\n' "$2" | wc -l)" -eq 1 ] && printf '%s\n' "$2" | grep -Eqx -- "$1"
}

[ $# -ge 4 ] || usage "at least four arguments"
login=$1
host=$2
project=$3
shift 3
matches '[a-z][a-z0-9-]{0,31}' "$login" || usage "no login: $login"
matches '[A-Za-z0-9][A-Za-z0-9.:-]*' "$host" || usage "no host: $host"
matches '[A-Za-z0-9][A-Za-z0-9._-]*' "$project" || usage "no project: $project"

now=$(date -u +%Y-%m-%dT%H:%M:%S)
records=
report=
for file in "$@"; do
    [ -f "$file" ] && [ -r "$file" ] || refuse "$file" "no readable file"
    # grep counts a last line without its newline, which wc does not.
    [ "$(grep -c '' "$file")" -eq 1 ] || refuse "$file" "not one line"
    IFS=' ' read -r kind blob _ <"$file"
    line="$kind $blob"
    # Only these characters go on, so nothing of the file can act in the script the host runs.
    matches 'ssh-ed25519-cert-v01@openssh\.com [A-Za-z0-9+/]+={0,2}' "$line" ||
        refuse "$file" "no Ed25519 certificate"
    # ssh-keygen checks the certificate's own signature as it reads it, and is handed exactly what would be laid.
    shown=$(printf '%s\n' "$line" | ssh-keygen -L -f - 2>/dev/null) ||
        refuse "$file" "ssh-keygen does not read it, or its signature fails"
    # Headings stand at eight spaces and a principal at sixteen, so a principal cannot read as a heading.
    field() { printf '%s\n' "$shown" | sed -n "s/^        $1: *//p" | head -n 1; }
    printf '%s\n' "$(field Type)" | grep -q ' user certificate$' || refuse "$file" "no user certificate"
    fingerprint=$(field 'Public key' | sed 's/^[^ ]* //')
    matches 'SHA256:[A-Za-z0-9+/]{43}' "$fingerprint" || refuse "$file" "no key fingerprint"
    signer=$(field 'Signing CA' | sed 's/^[^ ]* //; s/ .*//')
    principals=$(printf '%s\n' "$shown" | sed -n '/^        Principals:/,/^        Critical Options:/p' | sed '1d;$d; s/^ *//')
    matches "task:$(printf '%s' "$project" | sed 's/[.]/[.]/g'):[A-Za-z0-9][A-Za-z0-9._-]*" "$principals" ||
        refuse "$file" "its principal is not one task of $project: $principals"
    [ "$(field 'Critical Options')" = "(none)" ] || refuse "$file" "it carries a critical option"
    until=$(field Valid | sed -n 's/^from [^ ]* to //p')
    matches '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}' "$until" || refuse "$file" "no end to its validity"
    [ "$(expr "$until" \> "$now")" = 1 ] || refuse "$file" "it expired at $until UTC"

    name=$(printf '%s' "${fingerprint#SHA256:}" | tr '+/' '-_')
    records="$records
lay '$name' '$fingerprint' '$line'"
    report="$report
laid $fingerprint: $principals, signed by $signer, valid until $until UTC"
done

ssh -T -o BatchMode=yes -o ForwardAgent=no -o ClearAllForwardings=yes "$login@$host" sh -s <<REMOTE || {
set -u
umask 077
dir=\${XDG_STATE_HOME:-\$HOME/.local/state}/porelo/task-certificates
temporary=
laid=
finish() {
    status=\$?
    [ -z "\$temporary" ] || rm -f "\$temporary"
    [ "\$status" -eq 0 ] || for each in \$laid; do echo "left: \$each" >&2; done
}
trap finish EXIT
trap 'exit 130' HUP INT TERM
mkdir -p "\$dir" || exit 1
for left in "\$dir"/.laying-*; do
    if [ -e "\$left" ]; then
        echo "REFUSED: a run that died left \$left; look at it and remove it" >&2
        exit 1
    fi
done
stage=check
lay() { # <file name> <fingerprint> <certificate>
    target=\$dir/\$1.json
    if [ "\$stage" = check ]; then
        if [ -e "\$target" ] && { [ ! -r "\$target" ] || grep -q '"task"' "\$target"; }; then
            echo "REFUSED: \$target is Porelo's own record of \$2; nothing was laid" >&2
            exit 1
        fi
        return
    fi
    temporary=\$(mktemp "\$dir/.laying-XXXXXX") || exit 1
    printf '{"certificate":"%s"}\n' "\$3" >"\$temporary" || exit 1
    mv -fT "\$temporary" "\$target" || exit 1
    temporary=
    laid="\$laid \$target"
    # Its input is the rest of this script.
    if answer=\$(porelo task certificate "\$2" --json 2>&1 </dev/null); then
        case \$answer in
        *"\$3"*) return ;;
        esac
    fi
    echo "FAILED: Porelo does not find \$2 there and answered: \$answer" >&2
    exit 1
}
$records
stage=write
$records
REMOTE
    status=$?
    echo "FAILED: laying on $login@$host answered $status" >&2
    exit "$status"
}
printf '%s\n' "$report" | sed '/^$/d'
