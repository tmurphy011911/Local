-- Email captured on the phone-entry step of signup.
--
-- Stored as a plain profile attribute ONLY — phone (auth.users) remains the
-- auth identity. We deliberately do NOT push this into auth.users, which would
-- trigger Supabase's email-confirmation / second-OTP flow we don't implement.
--
-- Nullable: existing rows have no email, and the field may be optional for new
-- signups depending on client gating. No table rewrite (nullable add).
--
-- Security: email is PII. RLS on user_profiles must keep row reads scoped to
-- the owner; cross-user reads (e.g. share-token creator-name lookup) must
-- column-limit and never select email.
alter table public.user_profiles
  add column if not exists email text;
