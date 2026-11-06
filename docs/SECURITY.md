# Security

StreamControl is under development and has not undergone an external security assessment. Browser authorization selected the intended Brand Account in one debug test; refresh-token restoration, live title changes, and ending live broadcasts require real-channel validation before release.

Twitch sign-in and operations have fake-backed tests but have not yet been verified with a real Twitch account.

## Authentication and identity

The app requests the broad `youtube` scope through a browser Auth Tab with authorization code, PKCE S256, unpredictable state, exact callback validation, and a single-use pending request. Token requests include a DPoP proof signed by a per-connection Android Keystore key; Google's nonce challenge is retried once with a new proof. It checks granted scope and requires a refresh token; it reads the authorized channel and requires confirmation before saving. The saved channel ID is checked again on restoration and before each stream operation. A changed or ambiguous channel blocks operations. Tokens, codes, and authorization headers are not placed in UI state, navigation, or app logs.

Each channel's refresh token and identity are stored in a separate atomic app-private no-backup file encrypted with AES-GCM using a non-exportable Android Keystore key. Existing unbound connections are retained but require reconnecting to obtain DPoP-bound refresh tokens. Access tokens stay in memory; refresh is serialized per channel, and rotated refresh tokens replace that channel's encrypted record. Google's access tokens remain bearer tokens. Invalid credentials or failed decryption require reconnection. Android Keystore may use hardware-backed protection when available; it does not protect an active session against a fully compromised device. DPoP, multi-channel restoration, and token rotation are not yet verified with real Google accounts.

Google's documentation conflicts about Android custom URI schemes. The Console has an opt-in but Google warns of app impersonation. PKCE limits intercepted-code use but does not authenticate the initiating app. Auth Tab is required and no exported callback receiver is used. Release configuration and provider acceptance must be validated before distribution.

Disconnect invalidates the chosen channel's in-memory session, cancels current work, deletes that encrypted record and key, and attempts Google's revocation endpoint. A request already sent cannot be recalled. Revocation may affect other channels or clients for the same account and Google Cloud project; they may need to reconnect. On failure, local cleanup and server revocation are reported separately; Google Account permissions can be used to remove access.

Twitch uses the device authorization flow with a public client ID and the `channel:manage:broadcast` scope. The app displays a user code and opens Twitch's activation page. It includes Twitch's optional URL code only when it matches the user-facing code and differs from the device secret; the device secret is never included in the browser URL. A validated Twitch user ID identifies each channel. Twitch refresh tokens are encrypted in separate no-backup Keystore-backed records; access tokens stay in memory. Refresh requests are serialized, and the stored old token is invalidated locally before a refresh request because Twitch public-client refresh tokens are single use. An uncertain refresh requires reconnecting rather than reusing the old token. Twitch sessions do not use Google's DPoP mechanism.

Twitch disconnect clears the local session and attempts to revoke the access token. If revocation cannot be confirmed, the app reports local-only disconnection; access can also be removed in Twitch account settings. A request already sent cannot be recalled.

## Networking and broadcast operations

Authenticated YouTube API calls use HTTPS to fixed `www.googleapis.com` endpoints; OAuth calls use fixed Google endpoints. Redirects and automatic retries are disabled. Images use separate unauthenticated clients restricted to HTTPS `i.ytimg.com` for broadcast thumbnails and `yt3.googleusercontent.com` or `yt3.ggpht.com` for channel avatars. There is no developer-operated backend or intentional telemetry. Platform TLS validation remains enabled.

Twitch authorization and API requests are restricted to fixed HTTPS Twitch endpoints, with redirects and automatic retries disabled. Twitch images use a separate unauthenticated client restricted to `static-cdn.jtvnw.net`. Tokens are validated when a session is restored and periodically while the app is in the foreground. The app does not continuously poll the stream list.

Thumbnail URLs come from broadcast metadata, with no additional API lookup. The app makes at most one image request per URL per process; unavailable or evicted images show a placeholder. HTTP URLs for the exact YouTube image host are upgraded to HTTPS before any request; other hosts, credentials in URLs, and nonstandard ports are rejected. Debug diagnostics contain only sanitized outcomes and HTTP status codes, not image URLs or broadcast IDs.

Channel avatar URLs come from the channel metadata already requested to verify identity. They must use HTTPS on an allowed channel image host and are fetched without credentials or redirects. Unavailable images show a placeholder.

Only broadcasts reporting lifecycle `live` are listed. Before title writes the app checks channel and broadcast, reads current metadata, warns on a changed title, preserves the writable snippet fields, submits `videos.update`, and verifies the result. It sends the fetched ETag in If-Match; Google's conditional-update guarantees require further verification and a race may remain between read and write. Ambiguous writes are read back, not automatically retried.

Ending a stream requires a named confirmation. The app checks the same channel and broadcast ID and verifies the broadcast is live, then requests `liveBroadcasts.transition` to `complete`; success requires a subsequent status read. Failed or ambiguous transitions are not automatically retried. Ending a YouTube broadcast does not guarantee an external encoder stops transmitting.

For Twitch title changes the app checks the broadcaster and current live stream, reads the current title, and warns of a conflict before writing only the title. There is a race between the read and write because no conditional update is used. An uncertain write is checked but not automatically retried. The app does not provide an End Stream action for Twitch.

## Local data and reporting

The manifest disables cleartext and backups, and excludes local data from transfer. Google Play services and AndroidX may contribute permission-protected exported components to the merged manifest. Presets and theme remain local. No client secret is embedded. The app has not been independently audited; review dependencies, release signing, OAuth verification, account switching, revocation, and real test broadcasts before public use. Do not post credentials in issues; no private vulnerability-reporting channel has been established.
