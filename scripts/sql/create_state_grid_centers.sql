create table state_grid_centers (
  id uuid primary key default gen_random_uuid(),
  state_code text not null references states(code),
  label text not null,
  lat double precision not null,
  lng double precision not null,
  created_at timestamptz not null default now()
);

create index idx_state_grid_centers_state on state_grid_centers (state_code);

-- Seed with existing MA and RI grids
insert into state_grid_centers (state_code, label, lat, lng) values
  ('MA', 'Boston/East', 42.3601, -71.0589),
  ('MA', 'Worcester/Central', 42.2626, -71.8023),
  ('MA', 'Springfield/West', 42.1015, -72.5898),
  ('MA', 'Cape Cod/SE', 41.7688, -70.4974),
  ('MA', 'Lowell/NE', 42.6334, -71.3162),
  ('MA', 'Pittsfield/Berkshires', 42.4501, -73.2454),
  ('MA', 'New Bedford/South Coast', 41.6362, -70.9342),
  ('RI', 'Providence', 41.8240, -71.4128),
  ('RI', 'Newport/SE', 41.4901, -71.3128),
  ('RI', 'Woonsocket/North', 42.0029, -71.5145);
