# BitFuse add-on catalog (Cloudflare Worker + D1)

The Worker is the only bridge between BitFuse and Telegram. The Android app
never contains a Telegram bot token and users do not need to join the channel.
Trusted administrators can manage entries by posting in the configured channel
or sending a private message to the bot.

## Telegram admin post format

Only posts in the configured admin channel — or private chat with the bot —
are considered:

```text
ADD unified | Friendly name | https://addon.example
```

or, to remove a managed add-on:

```text
REMOVE unified
```

`REMOVE` prevents the entry from appearing on newly activated devices and from
being updated on existing ones. Existing installs are intentionally retained
by the app; catalog sync is additive and does not uninstall old add-ons.

`ADD` may also be written with one field per line after the command:

```text
ADD unified
Friendly name
https://addon.example
```

Give the separate catalog bot administrator access to the channel, or start a
private chat with it. Restrict posting to trusted administrators: each accepted
post can change what every activated BitFuse installation will contact. Set
`TELEGRAM_ADMIN_CHAT_ID` to the channel id (`-100…`) or to the numeric id of the
private chat.

## Provisioning

1. Create a D1 database with `wrangler d1 create bitfuse-addon-catalog` and
   update `database_id` in `wrangler.toml` with the returned id.
2. Run `wrangler d1 migrations apply bitfuse-addon-catalog --remote`.
3. Set secrets (never commit their values):

   ```powershell
   wrangler secret put ACTIVATION_PASSWORD
   wrangler secret put TOKEN_PEPPER
   wrangler secret put TELEGRAM_ADMIN_CHAT_ID
   wrangler secret put TELEGRAM_WEBHOOK_SECRET
   ```

   `TOKEN_PEPPER` should be a random value of at least 32 bytes. The bot token
   must belong to a new bot, not BitFuse's Telegram bridge bot; it is only
   needed when registering the webhook with Telegram and is not needed by the
   running Worker. Set
   `MAX_DEVICES` as a Worker secret/variable if you want an activation cap.
4. Deploy with `wrangler deploy`.
5. Register the webhook with Telegram using the helper. It prompts for the
   bot token without echoing it, generates a fresh webhook secret locally, and
   uploads that secret to Cloudflare without saving it:

   ```powershell
   .\register-webhook.ps1 -WebhookUrl "https://<worker>.<account>.workers.dev/telegram/webhook"
   ```

   Do not put the bot token in the app or in source control.
6. Set `ADDON_CATALOG_SERVER=https://<worker>.<account>.workers.dev` in the
   developer's ignored `local.properties` or the release build environment.
   This is a public service URL, not a credential. Without it the app refuses
   enrollment instead of sending a password to an unset or insecure endpoint.

## API

- `POST /v1/activate` with `{"password":"..."}` returns a one-time device
  bearer token and the current catalog. Activation requests are rate limited.
- `GET /v1/catalog` with `Authorization: Bearer <device token>` returns the
  current revision and add-on rows.
- `DELETE /v1/device` revokes the current device token.
- Telegram's webhook only accepts updates authenticated by its secret header
  and matching the configured admin chat or channel id.

The shared activation password is only for initial enrollment. The returned
per-device token is random, stored as a hash in D1 and should be kept encrypted
by the Android app. Rotate the shared password if it leaks and revoke devices
from D1 when needed.

## Local parser tests

Run `node --test src/index.test.mjs` from this directory. Node 24 or newer can
load the Worker TypeScript using its built-in type stripping; deploying still
requires Wrangler and a Cloudflare account.
