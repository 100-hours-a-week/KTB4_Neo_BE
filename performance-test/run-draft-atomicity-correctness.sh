#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080/api/perf/redis/drafts}"
RESULT_DIR="${RESULT_DIR:-performance/results/draft-atomicity/correctness}"
mkdir -p "$RESULT_DIR"

request_body() {
  local version="$1"
  local content="$2"
  jq -nc \
    --arg title "$content" \
    --arg body "body-$content" \
    --argjson version "$version" \
    '{
      title: $title,
      postBody: $body,
      postImage: "",
      contentVersion: $version,
      updatedAt: "2026-08-08T12:00:00",
      fallbackTitle: "base",
      fallbackPostBody: "body-base",
      fallbackPostImage: "",
      fallbackContentVersion: 10,
      fallbackUpdatedAt: "2026-08-08T11:00:00",
      dirtyScore: 1
    }'
}

initialize() {
  local draft_id="$1"
  local version="$2"
  local content="$3"
  local dirty="$4"
  jq -nc \
    --argjson id "$draft_id" \
    --argjson version "$version" \
    --arg content "$content" \
    --argjson dirty "$dirty" \
    '{
      draftId: $id,
      title: $content,
      postBody: ("body-" + $content),
      postImage: "",
      contentVersion: $version,
      updatedAt: "2026-08-08T11:00:00",
      dirty: $dirty
    }' |
    curl -fsS -X POST "$BASE_URL/initialize" \
      -H 'Content-Type: application/json' \
      --data-binary @- >/dev/null
}

autosave() {
  local strategy="$1"
  local draft_id="$2"
  local version="$3"
  local content="$4"
  request_body "$version" "$content" |
    curl -fsS -X PUT "$BASE_URL/$strategy/$draft_id/autosave" \
      -H 'Content-Type: application/json' \
      --data-binary @-
}

remove_dirty() {
  local strategy="$1"
  local draft_id="$2"
  local rdb_version="$3"
  curl -fsS -X POST "$BASE_URL/$strategy/$draft_id/remove-dirty" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"rdbContentVersion\":$rdb_version}"
}

assert_eq() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $message (expected=$expected actual=$actual)" >&2
    exit 1
  fi
}

for strategy in LUA WATCH RLOCK; do
  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 101 10 base false

  newest="$(autosave "$strategy" 101 12 newest)"
  stale="$(autosave "$strategy" 101 11 stale)"
  idem="$(autosave "$strategy" 101 12 newest)"
  conflict="$(autosave "$strategy" 101 12 conflicting)"
  state="$(curl -fsS "$BASE_URL/101/state")"

  assert_eq "$(jq -r .status <<<"$newest")" SAVED "$strategy newer request"
  assert_eq "$(jq -r .status <<<"$stale")" VERSION_CONFLICT "$strategy stale request"
  assert_eq "$(jq -r .status <<<"$idem")" IDEMPOTENT "$strategy idempotent request"
  assert_eq "$(jq -r .status <<<"$conflict")" CONTENT_CONFLICT "$strategy content conflict"
  assert_eq "$(jq -r .snapshot.contentVersion <<<"$state")" 12 "$strategy final version"
  assert_eq "$(jq -r .snapshot.title <<<"$state")" newest "$strategy final content"
  assert_eq "$(jq -r .dirty <<<"$state")" true "$strategy dirty registered"

  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 102 10 base true
  equal="$(remove_dirty "$strategy" 102 10)"
  equal_state="$(curl -fsS "$BASE_URL/102/state")"
  assert_eq "$(jq -r .removed <<<"$equal")" true "$strategy equal dirty removal"
  assert_eq "$(jq -r .dirty <<<"$equal_state")" false "$strategy equal dirty removed"

  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 103 11 newest true
  newer="$(remove_dirty "$strategy" 103 10)"
  newer_state="$(curl -fsS "$BASE_URL/103/state")"
  assert_eq "$(jq -r .removed <<<"$newer")" false "$strategy newer dirty retained result"
  assert_eq "$(jq -r .dirty <<<"$newer_state")" true "$strategy newer dirty retained"

  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 104 9 stale true
  older="$(remove_dirty "$strategy" 104 10)"
  older_state="$(curl -fsS "$BASE_URL/104/state")"
  assert_eq "$(jq -r .removed <<<"$older")" true "$strategy stale cache removal"
  assert_eq "$(jq -r .exists <<<"$older_state")" false "$strategy stale hash deleted"
  assert_eq "$(jq -r .dirty <<<"$older_state")" false "$strategy stale dirty deleted"

  curl -fsS -X POST "$BASE_URL/106/orphan-dirty" >/dev/null
  orphan="$(remove_dirty "$strategy" 106 0)"
  orphan_state="$(curl -fsS "$BASE_URL/106/state")"
  assert_eq "$(jq -r .removed <<<"$orphan")" true "$strategy orphan dirty removal"
  assert_eq "$(jq -r .dirty <<<"$orphan_state")" false "$strategy orphan dirty removed"

  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 105 10 base false
  mkdir -p "$RESULT_DIR/$strategy-concurrent"
  for thread in $(seq 1 10); do
    autosave "$strategy" 105 11 "thread-$thread" \
      >"$RESULT_DIR/$strategy-concurrent/$thread.json" &
  done
  wait
  saved_count="$(jq -r .status "$RESULT_DIR/$strategy-concurrent"/*.json | grep -c '^SAVED$' || true)"
  conflict_count="$(jq -r .status "$RESULT_DIR/$strategy-concurrent"/*.json | grep -c '^CONTENT_CONFLICT$' || true)"
  concurrent_state="$(curl -fsS "$BASE_URL/105/state")"
  assert_eq "$saved_count" 1 "$strategy concurrent saved count"
  assert_eq "$conflict_count" 9 "$strategy concurrent conflict count"
  assert_eq "$(jq -r .snapshot.contentVersion <<<"$concurrent_state")" 11 "$strategy concurrent final version"
  assert_eq "$(jq -r .dirty <<<"$concurrent_state")" true "$strategy concurrent dirty"

  for round in $(seq 1 100); do
    initialize 107 10 base true
    autosave "$strategy" 107 11 latest >"$RESULT_DIR/latest-$strategy.json" &
    save_pid=$!
    remove_dirty "$strategy" 107 10 >"$RESULT_DIR/remove-$strategy.json" &
    remove_pid=$!
    wait "$save_pid" "$remove_pid"
    race_state="$(curl -fsS "$BASE_URL/107/state")"
    assert_eq "$(jq -r .snapshot.contentVersion <<<"$race_state")" 11 "$strategy dirty race version round $round"
    assert_eq "$(jq -r .dirty <<<"$race_state")" true "$strategy dirty race retained round $round"
  done

  jq -n \
    --arg strategy "$strategy" \
    --argjson saved "$saved_count" \
    --argjson conflicts "$conflict_count" \
    '{
      strategy: $strategy,
      staleOverwrite: 0,
      latestDirtyLoss: 0,
      partialHashUpdate: 0,
      hashWithoutDirty: 0,
      unexpectedStatus: 0,
      concurrentSaved: $saved,
      concurrentContentConflicts: $conflicts,
      passed: true
    }' >"$RESULT_DIR/${strategy}.json"

  echo "$strategy correctness: PASS"
done
