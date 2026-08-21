# PCBU blind relay

This Worker carries already-encrypted unlock packets between a PC and its owner's phone. It never receives a Windows password, pairing encryption key, biometric data, or plaintext unlock request.

Production endpoint: `https://pcbu-relay.advsolar.workers.dev`

## Deploy

1. `npm install`
2. `npx wrangler login`
3. `npx wrangler d1 create pcbu-relay`
4. Put the returned database ID in `wrangler.jsonc`.
5. `npx wrangler d1 migrations apply pcbu-relay --remote`
6. `npm run deploy`

Accounts use a user-chosen ID and a minimum 12-character password. Device tokens and session tokens are returned once and stored only as SHA-256 hashes by the service. Relay messages expire after two minutes.
