# Spela

En liten Android-app som spelar webbradio efter **genre × decennium**.
Den hämtar stationer från den öppna katalogen [Radio Browser](https://www.radio-browser.info).
Ingen API-nyckel, inga externa bibliotek, APK under 200 kB.

```
┌──────────────────────────────┐
│ Välj musiktyp / Spelar: …    │
├───┬─────────┬───────┬───────┬──────┤
│ A │ Rock    │80-90  │90-00  │2000->│
│ B │ Jazz    │ …     │       │      │
│ C │ Country │       │       │      │
│ D │ Blues   │       │       │      │
│ E │ Pop     │       │       │      │
└───┴─────────┴───────┴───────┴──────┘
   [Start]   [Stop]   [Nästa]
          Close app (X)
```

## Så används appen

1. Tryck på en ruta i tabellen, till exempel **Rock 1980-1990**.
2. Tryck **Start**. Appen söker stationer och spelar den mest röstade som fungerar.
3. Tryck **Nästa** för att byta till nästa station inom samma val.
4. Tryck **Stop** för att stoppa.
5. Tryck **Close app** för att stoppa musiken och stänga appen.

Musiken fortsätter när skärmen släcks. En notis visar vad som spelas och har knapparna Nästa och Stopp.
Om du trycker på en annan ruta medan musiken spelar byter appen direkt till den nya musiktypen.

## Bygga och installera

Repot bygger APK:n själv med GitHub Actions vid varje push till `main`.

1. Pusha koden till GitHub-repot `spela`.
2. Gå till fliken **Actions** och vänta tills bygget är grönt (cirka 3–5 minuter).
3. Gå till **Releases** (högerspalten på repots startsida) och välj den senaste versionen.
4. Öppna sidan på Android-telefonen och ladda ner **spela.apk**.
5. Öppna filen. Första gången frågar telefonen om du vill tillåta installation från webbläsaren. Svara ja.
6. Tillåt notiser om appen frågar. Det behövs för att notisen ska synas.

Nya versioner installeras ovanpå den gamla. Du behöver inte avinstallera först.

### Bygga lokalt (valfritt)

Kräver Android SDK, till exempel via Android Studio.

```
./gradlew assembleRelease
```

APK:n hamnar i `app/build/outputs/apk/release/app-release.apk`.

## Ändra genrer eller decennier

Allt finns överst i `app/src/main/java/se/spela/app/MainActivity.java`:

- `GENRES` innehåller texten som visas.
- `GENRE_TAGS` innehåller söktaggen i Radio Browser, till exempel `rock` eller `jazz`.
- `DECADE_TAGS` innehåller decennietaggarna, till exempel `80s`, `90s` och `2000s`.

## Begränsningar

- **Antalet träffar varierar.** Decennietaggar sätts fritt av användarna i katalogen.
  - Rock och pop från 80- och 90-talet ger många träffar.
  - Kombinationer som Blues 1990-2000 ger få.
  - Om färre än tre stationer hittas fyller appen på med stationer som bara matchar genren.
- **Du väljer kanal, inte låt.** Stationen bestämmer vad som spelas.
- **Strömmar går ibland ner.** Appen hoppar då automatiskt till nästa station, högst sex gånger i rad.
- **Mobildata.** En ström på 128 kbit/s drar ungefär 60 MB per timme.

## Juridik

- Appen fungerar som en radiomottagare. Den spelar stationernas egna publika strömmar och lagrar eller spelar inte in något.
- Musiklicenserna (STIM, SAMI med flera) är stationernas ansvar.
- Repot innehåller ingen musik.
- Signeringsnyckeln `app/spela.keystore` ligger öppet i repot så att varje bygge går att installera som uppdatering. Det är godtagbart för en privat app. Byt nyckel och lägg den som GitHub Secret om appen ska spridas till andra.
