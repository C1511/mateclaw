#!/usr/bin/env bash
set -euo pipefail

# Snowflake ID precision checker for mateclaw-ui.
#
# Scans the Vue / TypeScript sources for patterns that round-trip a
# backend-issued Snowflake ID through JS Number, silently truncating the
# last digits whenever the value exceeds Number.MAX_SAFE_INTEGER (2^53-1).
#
# Backend-issued Snowflake IDs must remain strings throughout the UI because
# JavaScript Number cannot exactly represent every 64-bit integer.
#
# Run locally from mateclaw-ui:
#   bash scripts/check-snowflake-precision.sh
#
# Run in CI: invoked automatically from this package's `lint` and `build`
# scripts. Keeping the checker inside mateclaw-ui also makes those commands
# self-contained in the public repository, where private root scripts are not
# published.
#
# Allowlisting a real exception:
#   Append a trailing comment `// snowflake-precision-ok: <one-liner reason>`
#   on the SAME line as the match. Only do this when the value is provably
#   a small bounded integer (e.g. a per-stream sequence counter, a port
#   number, a retry budget) — never as a shortcut to silence a real bug.
#
# Exit codes:
#   0 — all checks clean
#   1 — one or more violations found

UI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_SRC="${UI_ROOT}/src"

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

if [[ ! -d "$UI_SRC" ]]; then
  red "Missing UI src directory: $UI_SRC"
  exit 1
fi

if ! command -v rg >/dev/null 2>&1; then
  red "ripgrep (rg) is required. Install with: brew install ripgrep"
  exit 1
fi

exit_code=0

# Run one grep check. Hits carrying the allowlist annotation are skipped.
#
# Args:
#   $1 — human-readable label printed when violations are found
#   $2 — rg flags ("-n" for fixed-string-ish, "-nP" for PCRE)
#   $3 — pattern
run_check() {
  local label="$1" flags="$2" pattern="$3"
  local hits
  # `|| true` so a "no match" exit-1 from rg doesn't trip `set -e`.
  hits=$(rg $flags "$pattern" "$UI_SRC" 2>/dev/null | grep -vF 'snowflake-precision-ok' || true)
  if [[ -n "$hits" ]]; then
    red "✘ $label"
    echo "$hits"
    echo
    exit_code=1
  fi
}

# (1) v-model.number on a field whose name ends in Id — Vue's .number
#     modifier runs looseToNumber on the bound value before write-back.
run_check 'v-model.number bound to an *Id field' \
  '-n' 'v-model\.number=".*[Ii]d"?'

# (2) Number() / parseInt() / +id explicit coercion of an *Id-named value.
run_check 'Number()/parseInt() on an *Id value' \
  '-nP' '(Number|parseInt)\(\s*\w*[Ii]d\b'

# (2b) Number()/parseInt() wrapping a member-access or call expression that
#      ends in an id — e.g. Number(localStorage.getItem('mc-workspace-id')),
#      Number(route.params.agentId), Number(store.currentKB.id). Check (2) only
#      catches a bare *Id identifier; these compound forms slip past it but
#      truncate just the same. The `\.[Ii]d\b` alternative covers a plain `.id`
#      property access (e.g. `.currentKB.id)`) that the camelCase `[a-z]Id`
#      and quoted `[Ii]d['"]` alternatives miss.
run_check 'Number()/parseInt() on an *Id member-access / lookup' \
  '-nP' '(Number|parseInt)\([^)]*(?:[a-z]Id\b|[Ii]d['"'"'"]|\.[Ii]d\b)'

# (3) <input type="number"> v-modeled to an *Id field. Vue's vModelText
#     runtime auto-applies looseToNumber whenever el.type === 'number',
#     even WITHOUT the `.number` modifier, so the input type itself is
#     the bug source.
run_check 'input[type="number"] bound to an *Id field' \
  '-nP' 'type="number"[^>]*v-model[^"]*[Ii]d"'

# (4) `typeof xxxId === 'number'` presence checks. These silently drop the
#     value when the backend hands us the string form (ToStringSerializer
#     output), masking the real problem as "field unset".
run_check "typeof <id> === 'number' silently drops string IDs" \
  '-nP' "typeof\s+\S*[Ii]d\b\s*===\s*'number'"

if [[ $exit_code -eq 0 ]]; then
  green '✓ Snowflake ID precision check: clean'
else
  echo
  red 'Snowflake ID precision violations found.'
  yellow 'Keep backend-issued Snowflake IDs as strings throughout the UI.'
  yellow 'If a hit is a confirmed small-integer exception, append'
  yellow '`// snowflake-precision-ok: <reason>` on the same line.'
fi

exit $exit_code
