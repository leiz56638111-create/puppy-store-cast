# Upstream patch provenance

Source: https://github.com/jqssun/android-airplay-server
Commit: a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea
Original paths: app/src/main/cpp/patches/UxPlay/0001 through 0004.
GPL-3.0; complete LICENSE retained here.

These patch copies have paths rebased for mirror/src/main/cpp/airplay.
Applied on top of this project's AirTV/UxPlay-derived tree, then modified for
standalone Media3 playback, request validation, bounded variable session IDs,
connection reuse, same-session new URLs, typed playback-state flags and JNI
snapshots. The final complete files in mirror/ are the source of truth.
