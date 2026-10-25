Grow-Tagebuch v304 – H5179 + Statistik

Basis: letzte stabile Grow-Tagebuch-Linie v301/v300.
Enthalten:
- vollständige Statistik-Navigation und Auswertungen (Gesamter Zeitraum standardmäßig, 7/30/90 Tage, Benutzerdefiniert)
- Govee H5179 BLE-Livewerte
- hardwarebestätigter H5179 Decoder: Manufacturer 0x8801, 9-Byte-Payload
- native Hintergrundaufzeichnung in SQLite
- 24h/7d/30d/Gesamt-Sensorverläufe + CSV
- Android-Homescreen-Widget
- Zuordnung des Sensors zu einer Umgebung
- lokaler Datenspeicher

Die zuvor separat getestete H5179-Test-App wurde nicht in die UI übernommen; ihre funktionierende BLE-/Widget-Technik wurde in die eigentliche Grow-Tagebuch-App integriert.

UI v304: einheitliches Formdesign und stabile Full-Width-Slider-Bottom-Sheet.


v305: Android-Zuruecktaste nutzt GrowAppBack: Dialog/Detail -> vorherige Seite -> Start -> erst dann App verlassen. Datums-Wochentag-Doppelung und Formabstaende korrigiert.
