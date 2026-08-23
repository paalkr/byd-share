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

Every published update **must** be signed with the *same* key, or it won't install over the
previous version. So the key is created once and kept forever — losing it means you can never
ship an in-place update again. Treat it like a password: back it up, keep it secret.

**1. Create the keystore once:**

```bash
keytool -genkey -v -keystore byd-share-release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 -alias byd-share
```

Keep `byd-share-release.jks` **outside** the repo (it's gitignored anyway) — e.g. `~/.android/`.

**2. Give the build the secrets.** The build resolves four fields — `storeFile`, `storePassword`,
`keyAlias`, `keyPassword` — in this order: `keystore.properties` → GNOME keyring → (else) the
debug key. Pick one source:

- **GNOME keyring (recommended on Linux — nothing in plaintext, no per-release step):**

  ```bash
  secret-tool store --label='byd-share storeFile'     service byd-share field storeFile      # type the .jks path
  secret-tool store --label='byd-share storePassword' service byd-share field storePassword
  secret-tool store --label='byd-share keyAlias'      service byd-share field keyAlias        # byd-share
  secret-tool store --label='byd-share keyPassword'   service byd-share field keyPassword
  ```

  Set once; every `assembleRelease` afterwards signs automatically (as long as your login keyring
  is unlocked, which it is in a normal desktop session).

- **`keystore.properties`** (gitignored) — simplest, but passwords sit in plaintext on disk:

  ```properties
  storeFile=/home/you/.android/byd-share-release.jks
  storePassword=…
  keyAlias=byd-share
  keyPassword=…
  ```

**3. Build:**

```bash
./gradlew assembleRelease      # APK at app/build/outputs/apk/release/app-release.apk
```

If none of the sources provide a complete set, the release build falls back to the debug key so
it still builds — but that APK is **not** publishable as an update.

### Backing up the key

The keyring/properties file is for *building*; it is not a backup. Store the durable copy in your
password manager (Keeper is fine — it's encrypted/zero-knowledge): attach the `byd-share-release.jks`
file and record `keyAlias`, `storePassword`, `keyPassword`. If the laptop dies, you restore the
`.jks` from Keeper, re-run the `secret-tool store` commands, and you're signing again.

> For fully hands-off releases you can instead sign in CI (e.g. GitHub Actions): store the
> base64'd keystore + the three secrets as encrypted Actions secrets and have a tag-triggered
> workflow build, sign, and attach the APK to the release. Keeper stays the human backup.

## Install on your phone

```bash
./deploy.sh                    # USB: enable USB debugging, plug in
./deploy.sh 192.168.1.42       # wireless: phone's adb IP (wireless debugging on)
```

Then look for **Send to BYD** in the app drawer and in the share sheet.

## License

MIT — see [LICENSE](LICENSE).
