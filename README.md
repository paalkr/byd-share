# Send to BYD

A small Android companion app that lets you **share a location from your phone straight into
your BYD car's factory Telenav navigation** — the way premium car apps do it. Share a Google
Maps place (or any `geo:` link) to **Send to BYD** and it lands in the car as a favourite, a
navigation target, or an extra stop on the active route.

The phone is only half of it. The car half is a small endpoint in the custom
[OverDrive](https://github.com/yash-srivastava/Overdrive-release) BYD head-unit app that binds
Telenav's external `IUserDataService` / `INavigationService` and does the actual work. The phone
app talks to that endpoint over your existing OverDrive tunnel, so it works whether the car is at
home or away.

> **Requires OverDrive with the Telenav bridge** (the `/api/telenav/*` endpoints). Without that
> running on your head unit there is nothing for this app to talk to. See the OverDrive project.

## What it does

Share a place, then pick one of:

- **Navigate here** — start turn-by-turn to it now (replaces any active route).
- **Add to route** — add it as a stop on the route you're already driving.
- **Save to Favourites** — drop it in Telenav's heart list for later.

The car brings Telenav to the foreground automatically when you navigate, so it actually shows up
on the head-unit screen.

## Why sideload

Same reason you're here at all: this is for people who have already sideloaded OverDrive onto
their BYD head unit. If that's you, one more phone app is nothing new.

## Setup

1. Install the APK (see below) and open **Send to BYD**, or open **Settings** from the share
   screen.
2. **Base URL** — your OverDrive address, the same host you open OverDrive on
   (e.g. `https://od-byd-seal.example.com`).
3. **Edge authentication** — how your OverDrive host is fronted:
   - **None** — the host is reachable directly (your own tunnel/VPN/LAN). Most setups.
   - **Cloudflare Access (service token)** — the host sits behind Cloudflare Access. Enter the
     service-token client id/secret; the Access app needs a **Service Auth** policy, not just
     Allow.
4. **OverDrive access code** — the 8-character code from OverDrive on the car (the same code you
   use to log in to OverDrive). The app exchanges it for a token and refreshes automatically.

All settings are stored encrypted on the device (`EncryptedSharedPreferences`); nothing is sent
anywhere except your own car endpoint (and the geocoding services below).

## Usage

1. Open a place in Google Maps (or any app that shares a location).
2. Tap **Share** → **Send to BYD**.
3. Adjust the name if you like, then tap **Navigate here**, **Add to route**, or **Save to
   Favourites**.

## How location resolution works

Google Maps' "Share" almost never hands over raw coordinates — it gives a short link
(`maps.app.goo.gl/…`). The app:

1. Receives the shared text (`ACTION_SEND`) or a `geo:` link (`ACTION_VIEW`).
2. Follows the short-link redirect to the real maps URL.
3. Extracts the coordinates, preferring the place marker (`!3d…!4d…`), then explicit query
   params (`?q=lat,lng`), then the map centre (`/@lat,lng`). Falls back to scanning the page body.
4. Only as a last resort, geocodes the place name via OpenStreetMap Nominatim.

The parsing lives in `MapsLinkResolver` and is covered by unit tests (`./gradlew test`).

## Privacy — what the app talks to

- **Your OverDrive endpoint** — the resolved place (name + coordinates) is POSTed there. This is
  your own car.
- **Google** — to unwrap a shared Maps short link to its real URL (an HTTP redirect follow).
- **OpenStreetMap Nominatim** — only when a share has no coordinates and needs geocoding; sent
  with an identifying User-Agent and rate-limited.

No analytics, no accounts, no third-party SDKs. Network permissions are just `INTERNET` and
`ACCESS_NETWORK_STATE`.

## Build

Standard Gradle + Android SDK. The Java toolchain is auto-provisioned, so you don't need a
specific JDK on `PATH`.

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # run the resolver unit tests
```

### Building a signed release

Release builds are signed from a **gitignored** `keystore.properties` at the repo root. Create a
keystore once, then point the file at it:

```bash
keytool -genkey -v -keystore byd-share-release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 -alias byd-share
```

```properties
# keystore.properties (never commit this)
storeFile=/absolute/path/to/byd-share-release.jks
storePassword=…
keyAlias=byd-share
keyPassword=…
```

```bash
./gradlew assembleRelease      # APK at app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is absent the release build falls back to the debug key so it still
builds — but publish updates with the same release key every time, or they won't install over
each other.

## Install on your phone

```bash
./deploy.sh                    # USB: enable USB debugging, plug in
./deploy.sh 192.168.1.42       # wireless: phone's adb IP (wireless debugging on)
```

Then look for **Send to BYD** in the app drawer and in the share sheet.

## License

MIT — see [LICENSE](LICENSE).
