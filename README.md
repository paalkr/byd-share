# Send to BYD

A small Android companion app that lets you **share a location from your phone straight into
your BYD car's Telenav favourites** — the way the Audi app works when you share a Google Maps
place to the car.

Share a place in Google Maps → pick **Send to BYD** → it lands in the car's favourite list.

This is the phone half. The car half is a small endpoint in
[OverDrive](https://github.com/OverDrive) (the custom BYD head-unit app) that binds Telenav's
`IUserDataService.addFavorite(...)` and stores the place. The phone app talks to that endpoint
over your existing OverDrive tunnel, so it works even when the car is away from home.

> Status: **proof of concept.** Right now the app registers as a share target, resolves the
> shared location to coordinates, and shows the exact payload that would be sent to the car.
> Actually sending it (the OverDrive endpoint) is the next step.

## Why sideload

Same reason you're here at all: this is for people who have already sideloaded OverDrive onto
their BYD head unit. If that's you, sideloading one more phone app is nothing new.

## How it works

Google Maps' "Share" almost never hands over raw coordinates — it gives a short link
(`maps.app.goo.gl/…`). The app:

1. Receives the shared text (`ACTION_SEND`) or a `geo:` link (`ACTION_VIEW`).
2. Follows the short-link redirect to the real maps URL.
3. Extracts the coordinates, preferring the place marker (`!3d…!4d…`), then explicit query
   params (`?q=lat,lng`), then the map centre (`/@lat,lng`). Falls back to scanning the page
   body if the URL carries none.
4. Shows the name, coordinates, and the JSON payload that will go to the car.

The parsing lives in `MapsLinkResolver` and is covered by unit tests (`./gradlew test`).

## Build

Everything is standard Gradle + Android SDK. The Java toolchain is auto-provisioned, so you
don't need a specific JDK on `PATH`.

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # run the resolver unit tests
```

## Install on your phone

```bash
./deploy.sh                    # USB: enable USB debugging, plug in
./deploy.sh 192.168.1.42       # wireless: phone's adb IP (wireless debugging on)
```

Then look for **Send to BYD** in the app drawer and in the share sheet.

## Roadmap

- Wire the resolved payload to the OverDrive `POST /api/telenav/favorites` endpoint (auth over
  the CF Access tunnel).
- OverDrive side: bind `TnNaviService` → `IUserDataService.addFavorite("Normal", place)`, and
  show an on-screen toast when a favourite arrives.
- Favourite-type picker (Home / Work / Normal).

## License

MIT — see [LICENSE](LICENSE).
