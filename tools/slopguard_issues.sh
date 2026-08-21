#!/usr/bin/env bash
# Turn a slopguard JSON report into review issues — one issue per method, forever.
#
# The dedupe key is the method's stable `id`, carried in an HTML comment marker in
# the issue body. A method already reported gets a comment on its existing issue,
# open or closed; a closed one is never reopened, on the theory that "we looked at
# this and decided no" is a decision the next release tag shouldn't overturn.
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

gh label create slopguard --color BFDADC --force \
  --description "Filed by slopguard on a release tag" >/dev/null

# Fetched once and matched locally rather than through the search API: search
# indexing lags issue creation, and a missed match means a duplicate issue.
existing=$(gh issue list --label slopguard --state all --limit 500 --json number,body,state)

jq -c --argjson t "$threshold" --argjson n "$limit" \
  '[.methods[] | select(.crap >= $t)] | sort_by(-.crap) | .[:$n] | .[]' "$report" |
while read -r m; do
  id=$(jq -r '.id' <<<"$m")
  crap=$(jq -r '.crap | .*10 | round / 10' <<<"$m")
  cov=$(jq -r '.coverage | round' <<<"$m")
  cyc=$(jq -r '.complexity' <<<"$m")
  cog=$(jq -r '.cognitiveComplexity' <<<"$m")
  loc=$(jq -r '"\(.file):\(.line)"' <<<"$m")
  marker="<!-- slopguard:${platform}:${id} -->"

  num=$(jq -r --arg mk "$marker" 'map(select(.body | contains($mk))) | .[0].number // empty' <<<"$existing")
  if [ -n "$num" ]; then
    state=$(jq -r --arg mk "$marker" 'map(select(.body | contains($mk))) | .[0].state' <<<"$existing")
    note="Still over threshold on \`$ref\`: wCRAP **$crap** at ${cov}% coverage ($loc)."
    [ "$state" = "CLOSED" ] && note="$note

This issue is closed and stays closed — reopen it yourself if the score matters again."
    gh issue comment "$num" --body "$note

[Run]($run_url)"
    echo "commented #$num for $id"
    continue
  fi

  gh issue create \
    --title "slop: $id (wCRAP $crap)" \
    --label slopguard --label ready-for-agent \
    --body "$marker
\`$id\` scores **wCRAP $crap** — cyclomatic $cyc, cognitive $cog, ${cov}% line coverage.

**Location:** \`$loc\`

Complex code the tests don't reach. Add tests for the uncovered branches, or split
the method until its score is under 30. Either is fine; the score is the check.

Filed by slopguard-$platform on \`$ref\`. [Run]($run_url)"
  echo "opened issue for $id"
done
