# scripts/archive

One-off backfills, data fixes and seed scripts that have already been run and
are not called by any workflow or other script. Kept for reference and in case
a similar repair is needed again. Relative imports point at `../lib` and
`../run_crawls`, so they still run from here, but read before re-running:
most assume data state from when they were written.
