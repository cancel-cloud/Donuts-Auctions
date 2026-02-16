# Shulker Exclusion Policy (V3.1.2)

## Current Policy
`DonutsAuctions` excludes all listings where `item_id` contains `shulker_box` (including all color variants).

## Why Shulkers Are Excluded
Shulker listings contain opaque container payloads. Without a full and reliable parser for contained NBT/items, one listing cannot be attributed to the real contained item values. This distorts:

- search relevance (example: searching for `iron` but listing is only visible as `shulker_box`)
- per-item analytics (min/avg/median become misleading)
- deal scoring and watchlist quality

## Data Handling Behavior
- Startup migration removes existing shulker rows from `ah_listings`.
- After removal, orphaned snapshots are pruned.
- Capture pipeline skips shulker listings so they are not inserted again.

## Future Re-enable Criteria
Shulkers can be re-enabled only after:

1. reliable contained-item extraction from NBT/components,
2. consistent attribution model (container price -> contained item valuation),
3. dedicated tests for parsing accuracy and analytics impact.
