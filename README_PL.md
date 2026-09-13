# Whisper Live Android v1.0.0

Osobna aplikacja oparta na projekcie Whisper Files / whisper.cpp.

## Funkcje

- przechwytuje dźwięk odtwarzany przez telefon przez Android `AudioPlaybackCapture` (Android 10+),
- nie korzysta z mikrofonu, choć Android wymaga uprawnienia `RECORD_AUDIO` także dla playback capture,
- zapisuje równolegle pełne nagranie WAV 16 kHz mono PCM,
- co ok. 6 s wysyła kolejne porcje do whisper.cpp i dopisuje transkrypcję do TXT,
- 0,6 s overlap + deduplikacja słów na granicach porcji,
- jeżeli model nie nadąża, nagrywanie nie jest zatrzymywane: WAV pozostaje kompletny, a aplikacja sygnalizuje potrzebę pełnej transkrypcji po nagraniu,
- przycisk `Ponownie transkrybuj całe zapisane nagranie` robi dokładniejszy post-pass po WAV w blokach 30 s / overlap 2 s,
- ustawiany czas automatycznego zatrzymania: 1–120 minut,
- foreground service + CPU wake lock + screen wake lock,
- Activity dodatkowo ustawia `FLAG_KEEP_SCREEN_ON`,
- zapis TXT i WAV przez systemowy selektor plików,
- arm64-v8a.

## Ograniczenia Androida

AudioPlaybackCapture nie potrafi przechwycić każdego dźwięku systemowego. Aplikacja źródłowa musi zezwalać na capture; treści DRM, rozmowy/VoIP i aplikacje, które jawnie blokują playback capture, mogą dawać ciszę. Android pokazuje systemową zgodę MediaProjection przed każdym nowym nagraniem.

## Build Windows

Uruchom PowerShell:

```powershell
.\build_windows.ps1
```

Wymagane: JDK 17+, Android SDK 36 i NDK 25.2.9519653. Skrypt może doinstalować brakujące SDK/NDK, jeśli `sdkmanager` jest dostępny.

Wyniki:

- `out/WhisperLive-v1.0.0-arm64-debug.apk`
- `out/WhisperLive-v1.0.0-arm64-release.apk`
