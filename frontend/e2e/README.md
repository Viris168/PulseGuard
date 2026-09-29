# End-to-end tests

They drive the real app in a headless browser, against the real backend, database and mail.
One run signs up a fresh `e2e-…@example.test` account and deletes it at the end.

```bash
docker compose up -d                        # Postgres, Redis, Mailpit (from the repo root)
./mvnw spring-boot:run                      # backend on :8080, with .env loaded
npm run dev                                 # frontend on :5173 (in frontend/)
npx playwright install chromium             # first time only
npm run test:e2e
```

Override the targets with `E2E_BASE_URL` and `E2E_MAILPIT_URL`. Each run first clears the dev
Redis login and email rate-limit counters (container `E2E_REDIS_CONTAINER`, default `pulseguard-redis-1`;
`none` to skip), since every run comes from the same IP and would otherwise hit the hourly cap. They are not in CI, which has
no running stack; the unit and component tests (`npm test`) are.
