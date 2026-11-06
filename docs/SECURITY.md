# Security

StreamControl is under development and has not undergone an independent security assessment. Some real-account flows have been tested, but broader testing of account restoration, multiple accounts, and stream management is still needed.

## Account access

YouTube and Twitch authorization takes place through their respective services. StreamControl requests access to manage your broadcasts; these permissions are broader than changing a stream title. Review the permissions shown by each service before approving access.

Saved account credentials are encrypted and stored in the app's private storage on your device. The app connects directly to the services and does not use a developer-operated backend. Android backups and device transfers are disabled for the app's local data.

Disconnecting an account removes its local credentials and attempts to revoke access with the provider. If revocation fails, the app reports that the disconnect was local only. You can also review and remove access in your Google or Twitch account settings. Revoking Google access may affect other channels or clients associated with the same Google account and project.

## YouTube sign-in limitation

YouTube sign-in uses a browser authorization flow and an Android custom URI scheme. Google warns that custom URI schemes can allow app impersonation, and its [OAuth documentation](https://developers.google.com/identity/protocols/oauth2/native-app) says they are no longer supported on Android. Google's console has provided an option to enable this flow, but continued support and release compatibility are not established.

The flow uses PKCE to help protect intercepted authorization codes, but this does not establish the identity of the app initiating authorization. The remaining impersonation risk has not been independently assessed. Review this limitation and validate the sign-in flow before public distribution. See the [Google OAuth setup guide](GOOGLE_OAUTH_SETUP.md) for configuration details.

## Stream actions

StreamControl checks the selected channel and stream before making changes. Ending a YouTube broadcast requires confirmation. Network failures can make the outcome of an operation uncertain; check the stream's current state before trying again. Ending a YouTube broadcast does not necessarily stop streaming software from sending video.

## Reporting a security concern

Do not post tokens, authorization codes, signing keys, or other credentials in public issues. A private vulnerability reporting channel has not yet been established.
