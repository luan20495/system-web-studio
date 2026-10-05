#!/usr/bin/env bash
# Operator-only: set the credentials of the FIRST system administrator. The API creates the account at its next start, and only
# when no enabled system administrator exists yet (so running this twice never creates a second admin).
# The password is typed silently, validated, written to the env file (mode 600) and never printed or logged.
#   ./scripts/create-first-admin.sh [env-file]        default: .env   (pilot: .run/public/public.env)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
ENVF="${1:-.env}"
[ -f "$ENVF" ] || { echo "Env file not found: $ENVF" >&2; exit 1; }
read -r -p "Admin username (3-40 chars: a-z 0-9 . _ -): " USERNAME
[[ "$USERNAME" =~ ^[a-z0-9][a-z0-9._-]{2,39}$ ]] || { echo "Invalid username." >&2; exit 1; }
read -r -s -p "Password (14+ chars, letters and digits): " PW; echo
read -r -s -p "Repeat password: " PW2; echo
[ "$PW" = "$PW2" ] || { echo "Passwords do not match." >&2; exit 1; }
[ ${#PW} -ge 14 ] || { echo "Password must have at least 14 characters." >&2; exit 1; }
[[ "$PW" =~ [A-Za-z] && "$PW" =~ [0-9] ]] || { echo "Password must contain letters and digits." >&2; exit 1; }
case "$(printf '%s' "$PW" | tr 'A-Z' 'a-z')" in *"$USERNAME"*|*password*|*changeme*|*admin1234*) echo "Password is too guessable." >&2; exit 1;; esac
printf '%s\n' "$PW" | python3 -c '
import re,sys
p,u=sys.argv[1:3]; pw=sys.stdin.readline().rstrip("\n")
s=open(p).read()
def put(s,k,v):
    line=f"{k}={v}"
    return re.sub(rf"^{k}=.*$",lambda m:line,s,flags=re.M) if re.search(rf"^{k}=",s,flags=re.M) else s.rstrip("\n")+"\n"+line+"\n"
s=put(put(s,"BOOTSTRAP_ADMIN_USERNAME",u),"BOOTSTRAP_ADMIN_PASSWORD",pw)
open(p,"w").write(s)
' "$ENVF" "$USERNAME"
chmod 600 "$ENVF"
echo "Saved to $ENVF. Restart the API; the account is created only if no system administrator exists yet."
