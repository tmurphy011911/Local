-- Atomic per-op plan mutations (Phase 2 of the plan-edit overhaul).
--
-- Replaces the existing read-modify-write `updatePlan(full_plan)` pattern,
-- which is unsafe under concurrent writers — two clients editing the same
-- plan would race-overwrite each other's changes because each one reads the
-- whole `days` JSON, mutates it locally, and writes it back as one blob.
--
-- These functions perform the read-modify-write inside a single UPDATE.
-- PostgreSQL's row-level lock serializes them, so concurrent ops merge
-- naturally instead of clobbering. Each function is also idempotent — safe
-- to retry on transient failures without producing duplicate state.
--
-- All functions run as SECURITY INVOKER, so the existing RLS policies on
-- `plans` apply unchanged: a user can only mutate a plan they own (or one
-- where `attendees_can_edit` is true and they're an attendee).

-- ── Helpers ──────────────────────────────────────────────────────────

create or replace function _plan_days_count(p_plan_id uuid)
returns int
language sql stable
as $$
    select coalesce(jsonb_array_length(days), 0)
    from plans
    where id = p_plan_id;
$$;

-- ── Slot-level ───────────────────────────────────────────────────────

create or replace function plan_remove_slot(
    p_plan_id uuid,
    p_day_index int,
    p_slot_id text
) returns void
language plpgsql
as $$
begin
    update plans
    set days = jsonb_set(
        days,
        array[p_day_index::text, 'slots'],
        coalesce(
            (select jsonb_agg(slot)
             from jsonb_array_elements(days -> p_day_index -> 'slots') slot
             where slot ->> 'id' is distinct from p_slot_id),
            '[]'::jsonb
        )
    )
    where id = p_plan_id
      and p_day_index >= 0
      and p_day_index < jsonb_array_length(days);
end;
$$;

create or replace function plan_add_slot(
    p_plan_id uuid,
    p_day_index int,
    p_slot jsonb
) returns void
language plpgsql
as $$
declare
    v_slot_id text := p_slot ->> 'id';
    v_already_present boolean;
begin
    -- Idempotency guard — a retried op shouldn't append a duplicate.
    select exists(
        select 1
        from jsonb_array_elements(coalesce(days -> p_day_index -> 'slots', '[]'::jsonb)) slot
        where slot ->> 'id' = v_slot_id
    )
    into v_already_present
    from plans
    where id = p_plan_id;

    if v_already_present then return; end if;

    update plans
    set days = jsonb_set(
        days,
        array[p_day_index::text, 'slots'],
        coalesce(days -> p_day_index -> 'slots', '[]'::jsonb) || jsonb_build_array(p_slot)
    )
    where id = p_plan_id
      and p_day_index >= 0
      and p_day_index < jsonb_array_length(days);
end;
$$;

create or replace function plan_swap_slot(
    p_plan_id uuid,
    p_day_index int,
    p_old_slot_id text,
    p_new_slot jsonb
) returns void
language plpgsql
as $$
begin
    update plans
    set days = jsonb_set(
        days,
        array[p_day_index::text, 'slots'],
        coalesce(
            (select jsonb_agg(
                case when slot ->> 'id' = p_old_slot_id
                     then p_new_slot
                     else slot
                end
            )
             from jsonb_array_elements(days -> p_day_index -> 'slots') slot),
            '[]'::jsonb
        )
    )
    where id = p_plan_id
      and p_day_index >= 0
      and p_day_index < jsonb_array_length(days);
end;
$$;

-- Patch a slot's fields by merging a jsonb object on top of it. Used for
-- reservation/ticket state changes — the caller passes only the fields
-- that should change (`{"reservation_state": "made", "reservation_party_size": 4}`)
-- and the rest are preserved.
create or replace function plan_set_slot_field(
    p_plan_id uuid,
    p_day_index int,
    p_slot_id text,
    p_patch jsonb
) returns void
language plpgsql
as $$
begin
    update plans
    set days = jsonb_set(
        days,
        array[p_day_index::text, 'slots'],
        coalesce(
            (select jsonb_agg(
                case when slot ->> 'id' = p_slot_id
                     then slot || p_patch
                     else slot
                end
            )
             from jsonb_array_elements(days -> p_day_index -> 'slots') slot),
            '[]'::jsonb
        )
    )
    where id = p_plan_id
      and p_day_index >= 0
      and p_day_index < jsonb_array_length(days);
end;
$$;

-- ── Day-level ────────────────────────────────────────────────────────

create or replace function plan_replace_day(
    p_plan_id uuid,
    p_day_index int,
    p_day jsonb
) returns void
language plpgsql
as $$
declare
    v_count int;
begin
    select coalesce(jsonb_array_length(days), 0) into v_count
    from plans where id = p_plan_id;

    if p_day_index < 0 then return; end if;

    if p_day_index < v_count then
        update plans
        set days = jsonb_set(days, array[p_day_index::text], p_day)
        where id = p_plan_id;
    else
        -- Out of range — append.
        update plans
        set days = coalesce(days, '[]'::jsonb) || jsonb_build_array(p_day)
        where id = p_plan_id;
    end if;
end;
$$;

create or replace function plan_remove_day(
    p_plan_id uuid,
    p_day_index int
) returns void
language plpgsql
as $$
begin
    update plans
    set days = coalesce(
        (select jsonb_agg(d)
         from jsonb_array_elements(days) with ordinality t(d, idx)
         where (idx - 1) <> p_day_index),
        '[]'::jsonb
    )
    where id = p_plan_id
      and p_day_index >= 0
      and p_day_index < jsonb_array_length(days);
end;
$$;

create or replace function plan_add_day(
    p_plan_id uuid,
    p_day jsonb
) returns void
language plpgsql
as $$
declare
    v_label text := p_day ->> 'label';
    v_already_present boolean;
begin
    -- Idempotency guard — same label means probably a retried op.
    select exists(
        select 1
        from jsonb_array_elements(coalesce(days, '[]'::jsonb)) d
        where d ->> 'label' = v_label
    )
    into v_already_present
    from plans
    where id = p_plan_id;

    if v_already_present then return; end if;

    update plans
    set days = coalesce(days, '[]'::jsonb) || jsonb_build_array(p_day)
    where id = p_plan_id;
end;
$$;

-- ── Plan-level columns ──────────────────────────────────────────────

create or replace function plan_set_name(p_plan_id uuid, p_name text)
returns void language sql as $$
    update plans set name = p_name where id = p_plan_id;
$$;

create or replace function plan_set_group_size(p_plan_id uuid, p_size int)
returns void language sql as $$
    update plans set group_size = p_size where id = p_plan_id;
$$;

create or replace function plan_set_attendees_can_edit(p_plan_id uuid, p_can_edit boolean)
returns void language sql as $$
    update plans set attendees_can_edit = p_can_edit where id = p_plan_id;
$$;
