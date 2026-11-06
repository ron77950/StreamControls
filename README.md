# StreamControl

<img src="app/src/main/res/drawable-nodpi/streamcontrol_launcher_art.png" alt="StreamControl icon" width="112">

StreamControl is an Android app for managing YouTube and Twitch livestreams from one place. Connect your channels, see which streams are live, and update their titles without opening each platform's dashboard.

**Status:** StreamControl is in development. Some real-account flows have been tested, but multi-account behavior, session restoration, and stream management still need broader device and account testing. No prebuilt APK is included in this repository.

## What it does

- Connect multiple YouTube and Twitch channels and see their active streams together, with the channel shown on each card.
- Edit a live stream's title and save reusable title presets on your device.
- End a YouTube broadcast after confirmation. To end a Twitch stream, stop it in your streaming software.
- Choose a light, dark, or system theme, and control account-connection notifications in Settings.
- Disconnect channels individually and request that the provider revoke access.

Ending a YouTube broadcast doesn't necessarily stop your streaming software from sending video. Stop the encoder separately when needed.

## Requirements

- An Android phone or tablet running Android 8.0 (API 26) or later, with internet access.
- A YouTube or Twitch channel with permission to use its platform's API. YouTube livestreaming must be enabled on the channel to manage live broadcasts.
- For YouTube, a browser that supports Android Auth Tab. StreamControl prefers a compatible Chrome installation, even if it isn't the default browser.
- For a source build, Android Studio and the SDK components requested by the project. YouTube sign-in also needs a Google OAuth client configured for the build's signing certificate.

## Build and run

1. Open the repository in Android Studio and let it install or sync the required SDK components.
2. If you want YouTube support, follow the [Google OAuth setup guide](docs/GOOGLE_OAUTH_SETUP.md) to configure YouTube Data API access and the Android client ID for your signing certificate. A source build without this configuration cannot connect to YouTube.
3. Select an Android device in Android Studio and run the app.

The project includes a public Twitch client ID; no Twitch client secret is embedded in the app. If you publish a separate fork or application, review [Twitch setup](docs/TWITCH_SETUP.md) for registering and configuring your own client. See the [development guide](docs/DEVELOPMENT.md) for build commands and test details.

## Connecting accounts

Open **Accounts** in StreamControl and choose a platform:

- **YouTube:** Sign in through Google, select the intended YouTube channel when prompted, and confirm the channel shown in StreamControl. Google asks for broad YouTube account access; it isn't limited to changing stream titles. Access also depends on your channel's eligibility and permissions and the Google project's OAuth audience and verification status.
- **Twitch:** Open Twitch from the connection screen, sign in and approve access. Enter the displayed code if Twitch asks for it. StreamControl requests permission to manage broadcast settings, which is broader than title editing. After approval, return to StreamControl to see the connected channel.

Twitch may open its Android app after approval on some devices. If it shows a black screen, return to StreamControl; the connection may already have succeeded. When permitted, StreamControl can show a notification after the account connects. Notifications can be turned off in **Settings → Notifications** or in Android's settings.

## Using streams and presets

The Streams page loads connected channels' live streams when the app restores connections or a channel is connected. Switching tabs doesn't refresh the list. Use **Refresh** to check for changes; each connected channel may require separate API requests. Stream actions also make the requests needed to check and confirm the result. YouTube API quota applies.

Select a stream to manage it. YouTube titles can be up to 100 characters and cannot contain `<` or `>`; Twitch titles can be up to 140 characters. Presets are shared locally between platforms, and the selected platform's title rules apply when you use one. Thumbnails aren't always available, particularly for private streams, so a placeholder may appear.

## Privacy and security

StreamControl communicates directly with Google, YouTube, and Twitch and loads images from their image hosts. It has no developer-operated backend, advertising, or analytics. Refresh tokens are encrypted using Android Keystore and kept in app-private storage; access tokens remain in memory. Presets and settings are stored locally.

Disconnecting removes the local connection and attempts to revoke provider access. If revocation fails, the app reports that the disconnect was local only. Google revocation may also affect other channels or clients associated with the same account and project. See the [security guide](docs/SECURITY.md) for details and limitations.

## Project documentation

- [Development](docs/DEVELOPMENT.md)
- [Google OAuth setup and troubleshooting](docs/GOOGLE_OAUTH_SETUP.md)
- [Twitch setup](docs/TWITCH_SETUP.md)
- [Security](docs/SECURITY.md)

## License

No license has been selected for the StreamControl project. Third-party notices for included dependencies are available in the app.
