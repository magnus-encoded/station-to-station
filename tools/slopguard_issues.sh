#!/usr/bin/env bash
# Turn a slopguard JSON report into review issues — one issue per method, forever.
#
# The dedupe key is the method's file-and-name, carried in an HTML comment marker
# in the issue body. A method already reported gets a comment on its existing
# issue, open or closed; a closed one is never reopened, on the theory that "we
# looked at this and decided no" is a decision the next release tag shouldn't
# overturn.
#
# The marker deliberately drops the `@line` suffix the analyzer puts on its `id`.
# It used to keep it, and the id is not stable: any edit *above* a method shifts
# its line and mints a new marker, so v1.4.2 re-filed three methods v1.4.1 had
# already filed (#377/#378/#379 against #328/#329/#330) while `BillScreen#ActRow`,
# which happened not to move, deduped correctly. Matching below tolerates both
# spellings so the issues filed under the old marker are still found.
#
# usage: slopguard_issues.sh <report.json> <platform> <ref>
set -euo pipefail

report=${1:?usage: slopguard_issues.sh <report.json> <platform> <ref>}
platform=${2:?}
ref=${3:-$GITHUB_REF_NAME}

# Higher than the analyzer's own "crappy" line (30): a release shouldn't file an
# issue for every method that merely brushes the threshold.
threshold=${SLOPGUARD_ISSUE_THRESHOLD:-50}
limit=${SLOPGUARD_ISSUE_LIMIT:-5}
run_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"

# Where the report's `file` paths resolve from, relative to the working directory
# each job runs this in (android/ and ios/ respectively).
case $platform in
  kotlin) src_prefix="app/src/main/java" ;;
  swift)  src_prefix="StationToStation" ;;
  *)      src_prefix="." ;;
esac

# A Compose @Composable or a SwiftUI `body` is at 0% line coverage in every run
# and always will be: unit tests never render either framework. Ranked by a
# coverage-weighted score they therefore sit permanently at the top, which made
# this a list of the biggest UI functions rather than a list of things to fix —
# and with only `limit` slots per release they crowded out real findings (v1.4.2
# pushed AppModel.createPlaylist, genuine testable logic, off the Swift list
# entirely in favour of four freshly-ported view bodies).
#
# Skipped for *issue filing* only. They stay in the JSON report the workflow
# uploads, so the score is still there to look at — it just stops being mistaken
# for a work queue.
#
# Read from source because the analyzer's JSON carries neither annotations nor
# return types; `kind` is only function/getter/initializer/method.
is_ui_declaration() {
  local file=$1 line=$2 endline=$3 name=$4
  local path="$src_prefix/$file"
  [ -f "$path" ] || return 1
  case $platform in
    kotlin)
      # `line` is the start of the whole declaration, KDoc and annotations
      # included, so @Composable can sit well below it — GigMediaBands reports
      # line 1954 and its `fun` is at 1975. Scan from there down to the `fun`.
      awk -v s="$line" -v e="$endline" -v n="$name" '
        NR < s { next }
        NR > e { exit }
        /^[[:space:]]*@Composable([[:space:]]|$)/ { found = 1; exit }
        $0 ~ ("(^|[^A-Za-z0-9_])fun[[:space:]]+" n "([^A-Za-z0-9_]|$)") { exit }
        END { exit (found ? 0 : 1) }
      ' "$path"
      ;;
    swift)
      # Scan the signature — from `line` to the brace that opens the body — for a
      # `some View` return. Not a fixed window: a wrapped parameter list puts the
      # return type well below the `func`, and NightGrid's `band(...)` carries its
      # `-> some View` nine lines down.
      awk -v s="$line" -v e="$endline" '
        NR < s { next }
        NR > e { exit }
        /some View/ { found = 1; exit }
        /\{[[:space:]]*$/ { exit }
        END { exit (found ? 0 : 1) }
      ' "$path"
      ;;
    *) return 1 ;;
  esac
}

gh label create slopguard --color BFDADC --force \
  --description "Filed by slopguard on a release tag" >/dev/null

# Fetched once and matched locally rather than through the search API: search
# indexing lags issue creation, and a missed match means a duplicate issue.
existing=$(gh issue list --label slopguard --state all --limit 500 --json number,body,state)

filed=0
# Sorted here, capped after the UI skip below — capping first would spend the
# slots on the declarations we are about to drop.
jq -c --argjson t "$threshold" \
  '[.methods[] | select(.crap >= $t)] | sort_by(-.crap) | .[]' "$report" |
while read -r m; do
  if [ "$filed" -ge "$limit" ]; then break; fi

  id=$(jq -r '.id' <<<"$m")
  name=$(jq -r '.name' <<<"$m")
  file=$(jq -r '.file' <<<"$m")
  line=$(jq -r '.line' <<<"$m")
  endline=$(jq -r '.endLine' <<<"$m")
  crap=$(jq -r '.crap | .*10 | round / 10' <<<"$m")
  cov=$(jq -r '.coverage | round' <<<"$m")
  cyc=$(jq -r '.complexity' <<<"$m")
  cog=$(jq -r '.cognitiveComplexity' <<<"$m")
  loc="$file:$line"

  if is_ui_declaration "$file" "$line" "$endline" "$name"; then
    echo "skipped $id (UI declaration — 0% coverage by construction)"
    continue
  fi

  # Line-less: `file#method`, the half of the analyzer's id that does not move.
  base=${id%@*}
  key="${platform}:${base}"
  marker="<!-- slopguard:${key} -->"

  # Matched by comparing each existing marker with its own `@line` stripped, so
  # an issue filed under the old line-bearing marker still matches — and exactly,
  # rather than by substring, so `#ActRow` cannot collide with `#ActRowHeader`.
  match=$(jq -r --arg key "$key" '
    map(select(
      (([(.body // "") | capture("<!-- slopguard:(?<k>[^ ]+) -->").k] | first)) as $mk
      | $mk != null and (($mk | sub("@[0-9]+$"; "")) == $key)
    )) | .[0] | if . == null then empty else "\(.number) \(.state)" end' <<<"$existing")

  if [ -n "$match" ]; then
    num=${match%% *}
    state=${match##* }
    note="Still over threshold on \`$ref\`: wCRAP **$crap** at ${cov}% coverage ($loc)."
    [ "$state" = "CLOSED" ] && note="$note

This issue is closed and stays closed — reopen it yourself if the score matters again."
    gh issue comment "$num" --body "$note

[Run]($run_url)"
    echo "commented #$num for $base"
    filed=$((filed + 1))
    continue
  fi

  gh issue create \
    --title "slop: $base (wCRAP $crap)" \
    --label slopguard --label ready-for-agent \
    --body "$marker
\`$base\` scores **wCRAP $crap** — cyclomatic $cyc, cognitive $cog, ${cov}% line coverage.

**Location:** \`$loc\`

Complex code the tests don't reach. Add tests for the uncovered branches, or split
the method until its score is under 30. Either is fine; the score is the check.

Filed by slopguard-$platform on \`$ref\`. [Run]($run_url)"
  echo "opened issue for $base"
  filed=$((filed + 1))
done
