"""Restore missing seed rows to PROD quiz_questions from the original bank file.

Insert-only. A row is restored only when BOTH its id and its normalized question
text are absent from the table (so nothing existing is touched or duplicated).

  python3 -I scripts/restore_quiz_bank.py            # dry run (default)
  python3 -I scripts/restore_quiz_bank.py --apply    # write

Rollback: every inserted id is written to restore_quiz_bank_<date>_ids.json;
delete exactly those ids to undo.
"""
import collections, datetime, json, os, re, sys, urllib.request

BANK = os.path.expanduser("~/Downloads/local_question_bank.json")
URL = "https://blifubxslaucshmlphdc.supabase.co/rest/v1/quiz_questions"
APPLY = "--apply" in sys.argv

key = next(l.split("=", 1)[1].strip().strip("\"'")
           for l in open(os.path.expanduser("~/push-dev.env"))
           if l.startswith("PROD_SERVICE_KEY="))
H = {"apikey": key, "Authorization": f"Bearer {key}", "Content-Type": "application/json"}


def norm(q):  # mirrors the generated column: lower + alnum-only
    return re.sub(r"[^a-z0-9]", "", q.lower())


def req(method, url, body=None, extra=None):
    r = urllib.request.Request(url, method=method, headers={**H, **(extra or {})},
                               data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(r) as resp:
        return json.loads(resp.read() or "null")


existing = []  # PostgREST caps each response at 1000 rows → page by id
while True:
    page = req("GET", URL + f"?select=id,state,difficulty,normalized_question&order=id&limit=1000&offset={len(existing)}")
    existing += page
    if len(page) < 1000:
        break
ids = {r["id"] for r in existing}
texts = {(r["state"], r["difficulty"], r["normalized_question"]) for r in existing}

bank = json.load(open(BANK))["bank"]
todo, per_cell = [], collections.Counter()
for state, rows in bank.items():
    for r in rows:
        k = (r["state"], r["difficulty"], norm(r["question"]))
        if r["id"] in ids or k in texts:
            continue
        texts.add(k)
        todo.append({c: r[c] for c in ("id", "state", "difficulty", "question", "answers", "generated_at")})
        per_cell[(r["state"], r["difficulty"])] += 1

print(f"table rows now: {len(existing)}   rows to restore: {len(todo)}")
for k in sorted(per_cell):
    print(" ", k, per_cell[k])
if todo:
    print("sample:", todo[0]["id"], "-", todo[0]["question"])

if not APPLY:
    print("\nDRY RUN — nothing written. Re-run with --apply to insert.")
    sys.exit(0)

inserted = []
for i in range(0, len(todo), 100):
    batch = todo[i:i + 100]
    out = req("POST", URL + "?on_conflict=state,difficulty,normalized_question", batch,
              {"Prefer": "resolution=ignore-duplicates,return=representation"})
    inserted += [r["id"] for r in out]
log = f"restore_quiz_bank_{datetime.date.today()}_ids.json"
json.dump(inserted, open(log, "w"))
print(f"\nINSERTED {len(inserted)} rows. Ids saved to {log} (rollback = delete these ids).")
