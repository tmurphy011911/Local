-- Fix: resolve_state_code mis-assigned state for border towns.
--
-- Path 2 (coordinate bbox fallback) ordered candidate states by NEAREST BBOX
-- CENTER. State bboxes overlap along shared borders, so a point can fall inside
-- two states' rectangles — and "nearest center" then favored whichever state's
-- center happened to be closer. For a wide state like PA (center far to the
-- west), an eastern-PA border town (e.g. Perkasie, 40.36, -75.29) sits inside
-- both PA's and NJ's bbox, and NJ's center is much closer — so it resolved to
-- NJ and the user landed on the wrong state leaderboard.
--
-- New ordering: prefer the state the point is MOST INTERIOR to — the larger the
-- distance to the nearest bbox edge, the more confidently the point is inside
-- that state. Perkasie is ~0.6deg inside PA's eastern edge but only ~0.27deg
-- from NJ's western edge, so it now correctly resolves to PA. This is still a
-- bbox heuristic (rectangles, not polygons), but it no longer breaks toward
-- whichever neighbor has the nearer center.
--
-- Path 1 (home-city name -> metro -> state_code) and the is_live preference are
-- unchanged. Pure CREATE OR REPLACE of the function — affects every consumer
-- (set_home_state trigger, change_home_city RPC, the leaderboard backfill) at
-- once. Additive: no signature change, no data touched. Existing rows keep their
-- stored home_state until re-derived (handled by the one-off cleanup).

create or replace function public.resolve_state_code(
  p_lat  double precision,
  p_lng  double precision,
  p_city text
)
returns text
language sql
stable
as $$
  select coalesce(
    -- Path 1: home city name -> metro -> state_code
    (select m.state_code
       from public.metros m
      where p_city is not null
        and m.name ilike p_city
      limit 1),
    -- Path 2: coordinate bounding box; prefer live states, then the state the
    -- point is MOST INTERIOR to (largest min-distance to any bbox edge).
    (select s.code
       from public.states s
      where p_lat is not null and p_lng is not null
        and p_lat between s.lat_min and s.lat_max
        and p_lng between s.lng_min and s.lng_max
      order by s.is_live desc,
               least(p_lat - s.lat_min, s.lat_max - p_lat,
                     p_lng - s.lng_min, s.lng_max - p_lng) desc
      limit 1)
  );
$$;
