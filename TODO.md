# Local — TODO

## Deploy

- [ ] Deploy `carousels` edge function so DB carousels get filtered by the requesting user's city/state (fixes MA-only carousels leaking to out-of-market users). Required after commit `1230676` on `claude/mobile-editing-support-BDTIO`.
  ```bash
  npx supabase functions deploy carousels --project-ref blifubxslaucshmlphdc
  ```
