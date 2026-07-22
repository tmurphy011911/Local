-- Taste graph — Phase 1: rich event features (entity extraction).
--
-- events.tags already exists as a TEXT[] (the "up to 5" cap is enforced in the
-- classifier prompt, NOT in the schema — so widening it to 12-15 is a code
-- change in scripts/classify_events.js, no migration needed for tags).
--
-- What DOES need columns: extracted ENTITIES. Entities are high-cardinality
-- (thousands of artists/brands) and must NOT be mixed into the low-cardinality
-- vibe/descriptor tag vocab, or feature co-occurrence turns to noise. They live
-- in their own array columns. team_name already exists for sports; performers
-- and brands are the general case.
--
-- Additive-only: ADD COLUMN with array default + CREATE INDEX. No destructive DDL.

alter table events
    -- Canonicalized performer/artist slugs, e.g. {'shane_gillis','matt_maeson'}.
    add column if not exists performers text[] not null default '{}',
    -- Festival / series / promoter / brand slugs, e.g. {'tree_house_brewing'}.
    add column if not exists brands text[] not null default '{}',
    -- Which controlled-vocab version tagged this row, so we can re-tag deltas
    -- when the vocabulary grows without re-running the whole corpus.
    add column if not exists tag_vocab_version text;

comment on column events.performers is
    'Canonicalized performer/artist slugs extracted by the classifier. High-cardinality entities, kept out of tags.';
comment on column events.brands is
    'Canonicalized festival/series/promoter/brand slugs. High-cardinality entities, kept out of tags.';
comment on column events.tag_vocab_version is
    'Controlled tag-vocabulary version this row was classified under (see scripts/taste/tag_vocabulary.js).';

-- GIN indexes so the taste scorer and carousel/explore queries can filter
-- "events where performers @> {shane_gillis}" / "tags && {craft-beer}" fast.
create index if not exists idx_events_performers_gin on events using gin (performers);
create index if not exists idx_events_brands_gin      on events using gin (brands);
create index if not exists idx_events_tags_gin        on events using gin (tags);
