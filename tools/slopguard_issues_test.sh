#!/usr/bin/env bash
# One check for the only logic in slopguard_issues.sh worth getting wrong:
# new method -> create, known method -> comment, closed method -> comment, never reopen.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/report.json" <<'JSON'
{"methods":[
 {"id":"Fresh.tangle()","crap":91.25,"coverage":0,"complexity":9,"cognitiveComplexity":12,"file":"a.kt","line":10},
 {"id":"Known.tangle()","crap":72.5,"coverage":20,"complexity":8,"cognitiveComplexity":9,"file":"b.kt","line":20},
 {"id":"Closed.tangle()","crap":61.0,"coverage":30,"complexity":7,"cognitiveComplexity":8,"file":"c.kt","line":30},
 {"id":"Fine.tidy()","crap":12.0,"coverage":90,"complexity":2,"cognitiveComplexity":1,"file":"d.kt","line":40}
]}
JSON

mkdir -p "$tmp/bin"
cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$@" >> "$GH_LOG"
if [ "$1 $2" = "issue list" ]; then
  cat <<'JSON'
[{"number":7,"state":"OPEN","body":"<!-- slopguard:kotlin:Known.tangle() -->\nold"},
 {"number":8,"state":"CLOSED","body":"<!-- slopguard:kotlin:Closed.tangle() -->\nold"}]
JSON
fi
exit 0
STUB
chmod +x "$tmp/bin/gh"

export GH_LOG="$tmp/log" PATH="$tmp/bin:$PATH"
: > "$GH_LOG"
"$here/slopguard_issues.sh" "$tmp/report.json" kotlin v9.9.9 >/dev/null

grep -q 'issue create .*Fresh.tangle()' "$GH_LOG" || { echo "FAIL: no issue for the fresh method"; exit 1; }
grep -q 'issue comment 7' "$GH_LOG" || { echo "FAIL: did not comment the open issue"; exit 1; }
grep -q 'issue comment 8' "$GH_LOG" || { echo "FAIL: did not comment the closed issue"; exit 1; }
grep -q 'issue reopen' "$GH_LOG" && { echo "FAIL: reopened a closed issue"; exit 1; }
grep -q 'Fine.tidy()' "$GH_LOG" && { echo "FAIL: filed a method under threshold"; exit 1; }
[ "$(grep -c 'issue create' "$GH_LOG")" = 1 ] || { echo "FAIL: wrong number of issues created"; exit 1; }
echo "ok"
