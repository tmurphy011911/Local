# Bo Reasoning Layer — labeled eval seed (Phase 0)

Starter labeled set for the classifier go/no-go gate (spec §10, §13.5). **Hand-seeded** (no transcripts exist to mine — see spec §10 ⚠️). ~55 rows; the real target is 200–300, grown from shadow-mode capture once shipped.

**How to read `context`:** the current surface state passed to the classifier (spec §5.3). `none` = fresh thread / no surface open. `suggestions(…)` = an event tray is open with the noted contents. `plan(…)` = a plan card is open.

**Harness note:** this table converts mechanically to the JSONL the offline harness consumes — one object per row: `{id, utterance, context, expected_intent, expected_target}`. Keep this `.md` as the human-reviewable source of truth; generate JSONL from it.

> ⚠️ **Review action:** labels below are my best read of the taxonomy — correct any you disagree with, they define "correct" for the whole eval. The **hard-case pairs** (same words, different context/label) in the last section are the most important to get right.

---

## EVENTS_SEARCH — fresh query
| id | utterance | context | intent | target |
|---|---|---|---|---|
| es01 | live music this week | none | EVENTS_SEARCH | NEW |
| es02 | things to do tonight | none | EVENTS_SEARCH | NEW |
| es03 | free events this weekend in cambridge | none | EVENTS_SEARCH | NEW |
| es04 | any comedy shows coming up | none | EVENTS_SEARCH | NEW |
| es05 | outdoor stuff for the kids saturday | none | EVENTS_SEARCH | NEW |
| es06 | trivia nights near me | none | EVENTS_SEARCH | NEW |
| es07 | surprise me | none | EVENTS_SEARCH | NEW | *(present-over-ask; not CLARIFY)* |
| es08 | i'm bored | none | EVENTS_SEARCH | NEW | *(present-over-ask)* |

## EVENTS_MODIFY — delta on an open tray
| id | utterance | context | intent | target |
|---|---|---|---|---|
| em01 | only the free ones | suggestions(mixed, has free) | EVENTS_MODIFY | CURRENT | *(FILTER, local)* |
| em02 | anything cheaper | suggestions(paid shows) | EVENTS_MODIFY | CURRENT | *(FILTER, local)* |
| em03 | drop the outdoor ones | suggestions(mixed) | EVENTS_MODIFY | CURRENT | *(FILTER; data-dep §5.4)* |
| em04 | show me more | suggestions(7 events) | EVENTS_MODIFY | CURRENT | *(EXPAND, research)* |
| em05 | sort by soonest | suggestions(open) | EVENTS_MODIFY | CURRENT | *(SORT, local)* |
| em06 | more like the first one | suggestions(open) | EVENTS_MODIFY | CURRENT | *(EXPAND)* |
| em07 | any of these tonight instead | suggestions(this weekend) | EVENTS_MODIFY | CURRENT | *(FILTER time, local)* |
| em08 | what about next weekend | suggestions(this weekend) | EVENTS_MODIFY | CURRENT | *(follow-up = modify time, research)* |

## VENUE_SINGLE
| id | utterance | context | intent | target |
|---|---|---|---|---|
| vs01 | what's going on at fenway? | none | VENUE_SINGLE | NEW |
| vs02 | tell me about the sinclair | none | VENUE_SINGLE | NEW |
| vs03 | what's the deal with time out market | none | VENUE_SINGLE | NEW |
| vs04 | is the paradise any good | none | VENUE_SINGLE | NEW |

## EVENT_SINGLE
| id | utterance | context | intent | target |
|---|---|---|---|---|
| ev01 | is the celtics game sold out | none | EVENT_SINGLE | NEW |
| ev02 | tell me more about that first one | suggestions(open) | EVENT_SINGLE | NEW | *(refers to a specific shown event)* |
| ev03 | when does the beyonce show start | none | EVENT_SINGLE | NEW |

## QUICK_FACT
| id | utterance | context | intent | target |
|---|---|---|---|---|
| qf01 | how much is the aquarium | none | QUICK_FACT | NEW |
| qf02 | is the ica open on mondays | none | QUICK_FACT | NEW |
| qf03 | what time does the mfa close | none | QUICK_FACT | NEW |

## INFO
| id | utterance | context | intent | target |
|---|---|---|---|---|
| in01 | what's new in town | none | INFO | NEW |
| in02 | what's the vibe in somerville | none | INFO | NEW |
| in03 | what should i know about the north end | none | INFO | NEW |

## PLAN_BUILD / precondition (spec §15.3)
| id | utterance | context | intent | target |
|---|---|---|---|---|
| pb01 | plan my saturday | none (device loc known) | PLAN_BUILD | NEW | *(rule 2: when+loc)* |
| pb02 | plan a date night | none | PLAN_BUILD | NEW | *(rule 2: occasion=vibe)* |
| pb03 | build me a day in providence | none | PLAN_BUILD | NEW | *(rule 2: loc+implied day)* |
| pb04 | make a plan with these | suggestions(selected 3) | PLAN_BUILD | NEW | *(rule 1: seeds)* |
| pb05 | plan something | none | PLAN_BUILD | NEW | *(classifier emits plan_build+low-conf; precondition **gate** converts to clarify — §15.3)* |
| pb06 | help me plan | none | PLAN_BUILD | NEW | *(gate-driven clarify, not classifier-driven)* |

## PLAN_MODIFY — on an open plan
| id | utterance | context | intent | target |
|---|---|---|---|---|
| pm01 | swap the 7pm | plan(2 days) | PLAN_MODIFY | CURRENT |
| pm02 | move dinner earlier | plan(open) | PLAN_MODIFY | CURRENT |
| pm03 | add fenway to that | plan(open) | PLAN_MODIFY | CURRENT |
| pm04 | drop the last stop | plan(open) | PLAN_MODIFY | CURRENT |
| pm05 | can we do sunday instead | plan(saturday) | PLAN_MODIFY | CURRENT |

## OUT_OF_SCOPE
| id | utterance | context | intent | target |
|---|---|---|---|---|
| os01 | write me a poem | none | OUT_OF_SCOPE | NEW |
| os02 | what's the weather in tokyo | none | OUT_OF_SCOPE | NEW |
| os03 | help me with my taxes | none | OUT_OF_SCOPE | NEW |
| os04 | who won the game last night | none | OUT_OF_SCOPE | NEW | *(borderline — sports score, not an event; confirm)* |

## CLARIFY — genuinely underspecified (no surface)
| id | utterance | context | intent | target |
|---|---|---|---|---|
| cl01 | what should i do | none | EVENTS_SEARCH | NEW | *(present-over-ask: answerable by a sensible default)* |
| cl02 | help | none | CLARIFY | — | *(bare help, no context — the one genuine classifier-driven clarify)* |

## Compound (spec §5.6)
| id | utterance | context | intent | secondary | target |
|---|---|---|---|---|---|
| co01 | what's at fenway this weekend | none | VENUE_SINGLE | EVENTS_SEARCH | NEW | *(venue primary, events embedded)* |
| co02 | any live music, and what's that new brewery about | none | EVENTS_SEARCH | VENUE_SINGLE | NEW | *(primary = first ask)* |

## Hard-case pairs — same words, context flips the label
These are the highest-value rows; they encode the §5.3 tiebreak and follow-up rules.
| id | utterance | context | intent | target | why |
|---|---|---|---|---|---|
| hc01 | what about sunday | suggestions(saturday) | EVENTS_MODIFY | CURRENT | follow-up on open tray |
| hc02 | what about sunday | none | EVENTS_SEARCH | NEW | no surface → fresh |
| hc03 | just jazz | suggestions(rock shows) | EVENTS_MODIFY | CURRENT | delta word "just" → filter |
| hc04 | live jazz | suggestions(rock shows) | EVENTS_SEARCH | NEW | names a new subject → fresh (tiebreak) |
| hc05 | add thai food | plan(open) | PLAN_MODIFY | CURRENT | delta on plan |
| hc06 | thai food near me | none | EVENTS_SEARCH | NEW | fresh (or multi-venue recs if that ships) |
| hc07 | more | suggestions(open) | EVENTS_MODIFY | CURRENT | expand tray |
| hc08 | more | plan(open) | PLAN_MODIFY | CURRENT | add a stop |
