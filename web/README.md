# Tally web

A thin React + TypeScript client for the Tally backend: create accounts, view balances and statements, and make transfers with end-to-end idempotency.

- `npm install`: install dependencies. If it fails with EPERM/EBUSY under OneDrive, pause OneDrive syncing (system tray, Pause syncing) and retry.
- `npm run dev`: start the dev server on http://localhost:5173; the backend must be running on 8080.
- `npm test`: run the vitest suite over the money, error, and idempotency-intent logic.

Copy `.env.example` to `.env.local` and set `VITE_API_TOKEN` to the backend's dev token.
