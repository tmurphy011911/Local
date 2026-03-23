create table metros (
  id uuid primary key default gen_random_uuid(),
  state_code text not null references states(code),
  name text not null,
  lat double precision not null,
  lng double precision not null,
  radius_miles int not null default 75,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

create index idx_metros_state on metros (state_code);
create index idx_metros_active on metros (is_active);

-- Seed with known metros
insert into metros (state_code, name, lat, lng) values
  ('MA', 'Boston', 42.3601, -71.0589),
  ('MA', 'Worcester', 42.2626, -71.8023),
  ('MA', 'Springfield', 42.1015, -72.5898),
  ('RI', 'Providence', 41.8240, -71.4128);
