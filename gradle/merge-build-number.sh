#!/bin/sh

current="$2"
other="$3"

number() {
    sed -n 's/^build_number=\([0-9][0-9]*\).*/\1/p' "$1" | head -n 1
}

ours=$(number "$current")
theirs=$(number "$other")
[ -n "$ours" ] || ours=0
[ -n "$theirs" ] || theirs=0

highest=$ours
[ "$theirs" -gt "$highest" ] && highest=$theirs

sed "s/^build_number=[0-9][0-9]*/build_number=$highest/" "$current" > "$current.tmp" && mv "$current.tmp" "$current"
