# Recording a review

`tools/merge-gate` reads reviews, so agents record them in one format. GitHub forbids approving your own PR and every agent posts as the same account, so a reviewer agent leaves a COMMENTED review whose final paragraph holds a line exactly `Verdict: approve` (or `Verdict: changes`) and its tool marker (`Generated with [Claude Code]` or `Written by Codex`), on the PR's current head commit. Put a screenshot link or image in that review for a PR that touches UI. A push after the review needs a fresh one.
