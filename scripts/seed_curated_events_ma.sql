-- Seed curated_events for MA with the 25 vetted iconic events.
-- Run once in the Supabase SQL editor after wiping any prior batches.
-- image_url is NULL on all rows — run scripts/backfill_curated_images.js
-- afterward to populate them via og:image / Wikipedia article images.
--
-- To redo this seed: DELETE FROM curated_events WHERE region = 'MA';
-- then re-run this file.

INSERT INTO curated_events (
  region, title, description, start_at, end_at,
  venue_name, venue_city, venue_state, venue_lat, venue_lng,
  source_url, curation_batch_id
) VALUES
  ('MA', 'Duckling Day',
   'The Mother''s Day tradition where families in duckling costumes parade through the Public Garden, celebrating Make Way for Ducklings.',
   '2026-05-10T14:00:00Z', '2026-05-10T18:00:00Z',
   'Boston Public Garden', 'Boston', 'MA', 42.3540, -71.0696,
   'https://friendsofthepublicgarden.org/events/duckling-day/', gen_random_uuid()),

  ('MA', 'Wake Up the Earth Festival',
   'Approaching its 48th year, the Jamaica Plain festival draws 7,500+ people every year to mark the start of spring, with a parade that begins around 11 AM from Curtis Hall.',
   '2026-05-02T15:00:00Z', '2026-05-02T21:00:00Z',
   'Jamaica Plain', 'Boston', 'MA', 42.3097, -71.1139,
   'https://spontaneouscelebrations.org/wake-up-the-earth/', gen_random_uuid()),

  ('MA', 'WBUR Festival',
   'The public radio fest''s second year, anchoring Memorial Day weekend now that Boston Calling is on hiatus.',
   '2026-05-29T14:00:00Z', '2026-05-30T23:59:00Z',
   'Boston', 'Boston', 'MA', NULL, NULL,
   'https://www.wbur.org/festival', gen_random_uuid()),

  ('MA', 'Boston Pride Parade & Festival',
   'Moved a week earlier than usual to align with World Cup activities. Parade kicks off at 11 AM in Copley Square, ending at Boston Common with an all-ages concert and festival noon–6 PM, followed by a block party 2–8 PM.',
   '2026-06-06T15:00:00Z', '2026-06-07T00:00:00Z',
   'Copley Square to Boston Common', 'Boston', 'MA', 42.3505, -71.0742,
   'https://www.bostonpride.org/', gen_random_uuid()),

  ('MA', 'FIFA World Cup at Boston Stadium',
   'Seven matches at Gillette, including group-stage heavyweights (England, France, Morocco), a Round of 32, and a quarterfinal.',
   '2026-06-13T00:00:00Z', '2026-07-09T23:59:59Z',
   'Gillette Stadium', 'Foxborough', 'MA', 42.0909, -71.2643,
   'https://www.fifa.com/fifaplus/en/tournaments/mens/worldcup/canadamexicousa2026', gen_random_uuid()),

  ('MA', 'Tanglewood 2026 Season',
   'Themed around America''s 250th, this year features a weeklong Yo-Yo Ma residency, the conducting debut of Joe Hisaishi (Studio Ghibli composer), plus Carrie Underwood, Weird Al, and Trombone Shorty. James Taylor July 3–4; Hugh Jackman with the Pops Aug. 11.',
   '2026-06-21T00:00:00Z', '2026-09-02T23:59:59Z',
   'Tanglewood', 'Lenox', 'MA', 42.3487, -73.3108,
   'https://www.bso.org/tanglewood', gen_random_uuid()),

  ('MA', 'Boston Harborfest',
   'One of the country''s biggest Fourth of July festivals, kicking off the MA250 weekend along the waterfront.',
   '2026-07-02T00:00:00Z', '2026-07-04T23:59:59Z',
   'Boston Waterfront', 'Boston', 'MA', 42.3601, -71.0489,
   'https://www.bostonharborfest.com/', gen_random_uuid()),

  ('MA', 'Boston Pops Fireworks Spectacular',
   'The official MA250 Signature Event on the Esplanade. Bigger fireworks, high-profile guests, and the biggest 4th of July crowd in a generation.',
   '2026-07-05T00:00:00Z', '2026-07-05T04:00:00Z',
   'Hatch Shell, Charles River Esplanade', 'Boston', 'MA', 42.3564, -71.0739,
   'https://www.julyfourth.org/', gen_random_uuid()),

  ('MA', 'Sail Boston / Sail250',
   'Tall Ships return for the first time in a generation. Parade of Sail July 11; free public ship boarding all week; fireworks July 11 and 15.',
   '2026-07-11T00:00:00Z', '2026-07-16T23:59:59Z',
   'Boston Harbor', 'Boston', 'MA', 42.3601, -71.0489,
   'https://sailboston.com/', gen_random_uuid()),

  ('MA', 'Fisherman''s Feast of the Madonna Del Soccorso',
   'Begun in Boston in 1910 based on a 16th-century tradition from Sciacca, Sicily, organized today by the descendants of the original immigrants with a procession of the Madonna through the streets.',
   '2026-08-13T00:00:00Z', '2026-08-16T23:59:59Z',
   'North End', 'Boston', 'MA', 42.3647, -71.0542,
   'https://www.fishermansfeast.com/', gen_random_uuid()),

  ('MA', 'Saint Anthony''s Feast',
   'Celebrated annually since 1919 in the North End, the "Feast of All Feasts" on the last Sunday of August features a ten-hour Grand Procession beginning Sunday at noon.',
   '2026-08-27T00:00:00Z', '2026-08-30T23:59:59Z',
   'North End', 'Boston', 'MA', 42.3647, -71.0542,
   'https://www.stanthonysfeast.com/', gen_random_uuid()),

  ('MA', 'Boston Film Festival',
   '42nd anniversary edition, with world premieres of feature films and new work from emerging filmmakers.',
   '2026-09-17T00:00:00Z', '2026-09-21T23:59:59Z',
   'Boston', 'Boston', 'MA', NULL, NULL,
   'https://bostonfilmfestival.org/', gen_random_uuid()),

  ('MA', 'Topsfield Fair',
   'America''s Oldest County Fair, founded in 1818, drawing between 450,000 and 500,000 people over its 10-day run. Giant pumpkin weigh-off, midway, farm animals — peak New England fall.',
   '2026-10-02T00:00:00Z', '2026-10-12T23:59:59Z',
   'Topsfield Fairgrounds', 'Topsfield', 'MA', 42.6362, -70.9583,
   'https://www.topsfieldfair.org/', gen_random_uuid()),

  ('MA', 'Salem Haunted Happenings',
   'The largest Halloween celebration in the world, with more than a million visitors expected for parties, parades, vendor fairs, walking tours, museums, attractions, and special events. Grand Parade is the first Thursday of October.',
   '2026-10-01T00:00:00Z', '2026-10-31T23:59:59Z',
   'Downtown Salem', 'Salem', 'MA', 42.5195, -70.8967,
   'https://www.hauntedhappenings.org/', gen_random_uuid()),

  ('MA', 'Head of the Charles Regatta',
   'The world''s largest 3-day rowing event, ~300K spectators lining the Charles.',
   '2026-10-16T00:00:00Z', '2026-10-18T23:59:59Z',
   'Charles River', 'Cambridge', 'MA', 42.3656, -71.1040,
   'https://www.hocr.org/', gen_random_uuid()),

  ('MA', 'Boston Book Festival',
   'The largest literary festival in New England, attracting an audience of 15,000 every October. Free, Copley Square + Roxbury.',
   '2026-10-17T13:00:00Z', '2026-10-17T23:59:00Z',
   'Copley Square', 'Boston', 'MA', 42.3498, -71.0773,
   'https://bostonbookfest.org/', gen_random_uuid()),

  ('MA', 'Massachusetts General Election',
   'Governor, U.S. Senate, and multiple competitive House seats on the ballot — one of the most consequential state elections in years.',
   '2026-11-03T12:00:00Z', '2026-11-04T05:00:00Z',
   'Statewide', NULL, 'MA', NULL, NULL,
   'https://www.sec.state.ma.us/divisions/elections/', gen_random_uuid()),

  ('MA', 'America250 Year-Long Programming',
   'The umbrella for hundreds of Revolution 250 events statewide: reenactments, exhibits (Old State House "Road to Revolution"), the Knox Trail 250 series, and Michael Dowling''s "100,000 stories of belonging" cloth-rope project.',
   '2026-01-01T00:00:00Z', '2026-12-31T23:59:59Z',
   'Statewide', NULL, 'MA', NULL, NULL,
   'https://revolution250.org/', gen_random_uuid()),

  ('MA', 'Nova Scotia Christmas Tree Lighting',
   'Canada''s annual thank-you tree kicks off the Boston holiday season on the Common.',
   '2026-12-03T22:00:00Z', '2026-12-04T02:00:00Z',
   'Boston Common', 'Boston', 'MA', 42.3554, -71.0655,
   'https://www.boston.gov/departments/parks-and-recreation/tree-lighting', gen_random_uuid()),

  ('MA', 'First Night Boston',
   'Running since 1976, the country''s oldest NYE arts festival: ice sculptures, City Hall Plaza music, 7 PM Common fireworks, and midnight Harbor fireworks — all free.',
   '2026-12-31T17:00:00Z', '2027-01-01T05:00:00Z',
   'Downtown Boston', 'Boston', 'MA', 42.3601, -71.0589,
   'https://firstnightboston.org/', gen_random_uuid()),

  ('MA', 'Lunar New Year Parade',
   'Lion dances, firecrackers, and dim sum crawls kick off the Year of the Sheep in Boston''s Chinatown.',
   '2027-02-21T16:00:00Z', '2027-02-21T21:00:00Z',
   'Chinatown', 'Boston', 'MA', 42.3518, -71.0622,
   'https://chinatownmainstreet.org/', gen_random_uuid()),

  ('MA', 'South Boston St. Patrick''s Day / Evacuation Day Parade',
   'Traditionally held each year on the Sunday closest to March 17, attracting up to a million or more spectators depending on the weather.',
   '2027-03-14T17:00:00Z', '2027-03-14T22:00:00Z',
   'South Boston', 'Boston', 'MA', 42.3381, -71.0476,
   'https://southbostonparade.org/', gen_random_uuid()),

  ('MA', 'Patriots'' Day Reenactments',
   'Dawn reenactment on Lexington Green, Old North Bridge ceremony in Concord — the original "shot heard round the world" moments.',
   '2027-04-19T09:00:00Z', '2027-04-19T20:00:00Z',
   'Lexington & Concord', 'Lexington', 'MA', 42.4473, -71.2245,
   'https://www.nps.gov/mima/planyourvisit/patriots-day.htm', gen_random_uuid()),

  ('MA', 'Boston Marathon, 131st Running',
   'Hopkinton to Copley, Patriots'' Day. Always a citywide moment.',
   '2027-04-19T13:00:00Z', '2027-04-19T22:00:00Z',
   'Hopkinton to Copley Square', 'Boston', 'MA', 42.3496, -71.0782,
   'https://www.baa.org/races/boston-marathon', gen_random_uuid()),

  ('MA', 'Boston Calling Returns',
   'Promoters confirmed they''ll be back in June 2027 after skipping 2026. First festival back, expect a stacked lineup.',
   '2027-06-04T00:00:00Z', '2027-06-06T23:59:59Z',
   'Harvard Athletic Complex', 'Allston', 'MA', 42.3665, -71.1247,
   'https://bostoncalling.com/', gen_random_uuid());
