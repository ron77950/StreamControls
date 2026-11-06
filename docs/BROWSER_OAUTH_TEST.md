# Browser authorization validation

Browser authorization is now the normal connection path. The former debug-only experiment demonstrated channel selection for one Brand Account, but live discovery, title updates, ending a broadcast, token refresh, and restoration still need real-account validation. See [Google OAuth setup](GOOGLE_OAUTH_SETUP.md) and [Security](SECURITY.md) for configuration and risks.

When YouTube enables live streaming, verify the intended channel, restart the app to check restoration, and check that an empty stream list loads. Start a disposable test broadcast, check discovery and title, then explicitly submit a temporary title change and verify the title in YouTube Studio. Finally, explicitly confirm End Stream and verify YouTube reports the broadcast ended; stop the encoder separately. No automated test changes or ends a real broadcast.
