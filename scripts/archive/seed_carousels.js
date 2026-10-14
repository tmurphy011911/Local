const { getSecrets, SUPABASE_URL } = require("../lib/secrets");

let SUPABASE_SERVICE_KEY;

const carousels = [
  { city: "boston", slug: "top_weekly",    title: "Top Events This Week", subtitle: "The best things to do this week", filter_type: "top_weekly", filter_value: null,         sort_order: "start_at", position: 0, is_active: true },
  { city: "boston", slug: "this_weekend",  title: "This Weekend",      subtitle: "Events happening soon",           filter_type: "date_range",  filter_value: "this_weekend", sort_order: "start_at", position: 1, is_active: true },
  { city: "boston", slug: "music",         title: "Live Music",        subtitle: "Concerts and shows",              filter_type: "category",    filter_value: "music",        sort_order: "start_at", position: 2, is_active: true },
  { city: "boston", slug: "arts",          title: "Arts & Culture",    subtitle: "Museums, galleries, theatre",     filter_type: "category",    filter_value: "arts",         sort_order: "start_at", position: 3, is_active: true },
  { city: "boston", slug: "sports",        title: "Sports",            subtitle: "Games and matches",               filter_type: "category",    filter_value: "sports",       sort_order: "start_at", position: 4, is_active: true },
  { city: "boston", slug: "free",          title: "Free Events",       subtitle: "No ticket required",              filter_type: "tag",         filter_value: "free",         sort_order: "start_at", position: 5, is_active: true },
  { city: "boston", slug: "nightlife",     title: "Nightlife",         subtitle: "Bars, clubs, late night",         filter_type: "category",    filter_value: "nightlife",    sort_order: "start_at", position: 6, is_active: true },
];

async function seedCarousels() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("Inserting carousels into Supabase...\n");

  const res = await fetch(`${SUPABASE_URL}/rest/v1/carousels`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates",
    },
    body: JSON.stringify(carousels),
  });

  if (!res.ok) {
    const body = await res.text();
    console.error(`Error: ${res.status} ${body}`);
    console.log("\nIf the table doesn't exist yet, run the SQL in scripts/create_carousels.sql first.");
    process.exit(1);
  }

  console.log(`Inserted ${carousels.length} carousels successfully.`);
  carousels.forEach((c) => {
    console.log(`  [${c.position}] ${c.slug} → ${c.title} (${c.filter_type})`);
  });
}

seedCarousels().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
