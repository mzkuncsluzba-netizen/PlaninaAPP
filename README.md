# PlaninaAPP

Aplikacija za pohodnike: naložiš traso (.gpx), med hojo spremljaš čas in napredek, po pohodu pa
aktivnost z ure primerjaš s traso, z načrtom in s prejšnjimi poskusi.

- **Spletna verzija:** https://mzkuncsluzba-netizen.github.io/PlaninaAPP/
- **Android:** https://github.com/mzkuncsluzba-netizen/PlaninaAPP/releases/latest/download/PlaninaAPP.apk

## Kaj zna
- Snemanje poti s telefonom in izvoz v .gpx.
- Vodenje po trasi: preostala razdalja, ocena časa do cilja, prihod, primerjava s ciljnim časom.
- Prenos aktivnosti neposredno z ure **Suunto Ambit 3** prek Bluetootha (samo Android), ali uvoz .gpx iz aplikacije Suunto.
- Analiza aktivnosti po odsekih trase.
- Priprava trase za Ambit (največ 100 točk).
- Posodobitve znotraj aplikacije (⋮ → Preveri posodobitve).

## Zgradba
- `index.html`: celotna aplikacija (spletni del). Ob vsaki spremembi povečaj `planina-version`.
- `android/`: Android lupina (WebView), deljenje datotek, posodobitve in povezava z uro.
- `android/app/src/main/cpp/`: protokol za uro Ambit (libambit, iz Sommeta).
- APK zgradi GitHub Actions ob vsakem pushu na `main` (`.github/workflows/android.yml`).

## Licenca
GNU GPLv3. Zasluge za kodo za povezavo z uro so v `CREDITS.md`.
