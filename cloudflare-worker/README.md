# Digitech Algorithm Cloudflare Worker

This Worker keeps the Twelve Data API key on Cloudflare instead of embedding it in the Android APK.

## Deploy from the Cloudflare dashboard

1. Open https://dash.cloudflare.com/ and sign in.
2. Open **Workers & Pages** → **Create** → **Create Worker**.
3. Name it `digitech-market-data`, then deploy the starter Worker.
4. Open the new Worker → **Edit code**.
5. Replace the starter code with the complete contents of `cloudflare-worker/src/index.js` from this repository branch, then **Save and Deploy**.
6. Open **Settings** → **Variables and Secrets** → **Add**.
7. Add a secret named exactly `TWELVE_DATA_API_KEY`. Paste your Twelve Data key into the Cloudflare secret field and save it. Do not paste the key into GitHub, chat, or Android.
8. Copy the Worker URL, for example `https://digitech-market-data.<your-subdomain>.workers.dev`.

## Verify deployment

Open `https://<your-worker-host>/health`. Expected JSON:

```json
{"ok":true,"service":"digitech-market-data-gateway"}
```

The historical endpoint is `/time_series?symbol=EUR/USD&interval=5min&outputsize=5000`.
The live stream endpoint is `wss://<your-worker-host>/ws`.

The Worker accepts only a small set of timeframes and caps historical output at 5,000 candles. It forwards the provider's WebSocket subscription and heartbeat messages without exposing the API key.

## Important

- Keep the Twelve Data key as a Cloudflare **Secret**, not a plain variable.
- Never add the key to source control or share it in chat.
- This Worker is not yet connected to the Android client. The Android test branch must be configured with the deployed Worker URL in the next integration step.
- Test only. Do not merge the test branch into the main app until the live data and chart behavior have been verified on-device.
