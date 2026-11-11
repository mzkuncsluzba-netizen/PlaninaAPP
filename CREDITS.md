# Zasluge

PlaninaAPP se z uro Suunto Ambit 3 povezuje s pomočjo kode iz dveh odprtokodnih projektov.

## Sommet
- Avtor: André Faria
- Vir: https://github.com/skinnie/sommet (stanje ob prevzemu: commit `2ce8eb3`, 9. 11. 2026)
- Licenca: GNU GPLv3
- Prevzeto:
  - `android/app/src/main/cpp/jni_bridge.cpp` in mapa `libambit/` brez sprememb,
  - `shared/device_driver_ambit3_*` iz `shared/libambit/`,
  - `AmbitBleModule.java`, predelan iz Sommetovega `AmbitBleModule.kt` (Kotlin/React Native) v samostojno Javo.

## openambit / libambit
- Avtor: Emil Ljungdahl in sodelavci
- Vir: https://github.com/openambitproject/openambit
- Licenca: GNU GPLv3
- Knjižnica `libambit`, ki jo uporablja Sommet in z njim PlaninaAPP, izvira iz tega projekta.

## Licenca PlaninaAPP
Ker vsebuje kodo pod licenco GPLv3, je celotna PlaninaAPP objavljena pod GNU GPLv3 (datoteka `LICENSE`).

Ostalo:
- ocena časa hoje po normi DIN 33466,
- ikona po vzoru Knafelčeve markacije.
