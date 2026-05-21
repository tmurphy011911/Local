// Load test for venue search queries (Places tab + Bo's search_venues).
//
// Two flavors of query alternate per iteration:
//   1. by-name lookup (ilike) — used when Bo has a venue name from a chat
//   2. by-category nearby — used by the Places tab grid

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  COMMON, randomLatLng, pick, THRESHOLDS,
} from '../lib/common.js';

const VENUE_NAMES = ['museum', 'cafe', 'pub', 'theatre', 'park', 'club', 'studio', 'hall'];
const VENUE_CATS = ['restaurant', 'bar', 'museum', 'theater', 'music_venue', 'comedy_club'];

export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '1m',  target: 5 },
    { duration: '30s', target: 50 },
    { duration: '1m',  target: 50 },
    { duration: '30s', target: 100 },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: THRESHOLDS.fast,
};

export default function () {
  const flavor = Math.random() < 0.5 ? 'name' : 'category';

  let url;
  if (flavor === 'name') {
    const fragment = pick(VENUE_NAMES);
    url =
      `${COMMON.SUPABASE_URL}/rest/v1/venues` +
      `?name=ilike.*${fragment}*` +
      `&select=id,name,city,lat,lng,public_interest_rank,photo_url` +
      `&order=public_interest_rank.desc.nullslast&limit=20`;
  } else {
    const cat = pick(VENUE_CATS);
    const { lat, lng } = randomLatLng();
    // Coarse 0.3-degree bbox (~20mi). Mirrors the app's geo-bbox query
    // pattern on the Places tab.
    const dLat = 0.3, dLng = 0.4;
    url =
      `${COMMON.SUPABASE_URL}/rest/v1/venues` +
      `?category=eq.${cat}` +
      `&lat=gte.${(lat - dLat).toFixed(4)}&lat=lte.${(lat + dLat).toFixed(4)}` +
      `&lng=gte.${(lng - dLng).toFixed(4)}&lng=lte.${(lng + dLng).toFixed(4)}` +
      `&select=id,name,city,lat,lng,public_interest_rank,photo_url` +
      `&order=public_interest_rank.desc.nullslast&limit=20`;
  }

  const res = http.get(url, {
    headers: COMMON.anonHeaders(),
    tags: { name: 'venue-search', flavor },
    timeout: '5s',
  });

  check(res, {
    'venue 200': (r) => r.status === 200,
  });

  sleep(0.4);
}
