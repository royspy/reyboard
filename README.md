# বাংলা কিবোর্ড – Bangla Android keyboard (Kotlin)

## Build
1. Install Android Studio (Koala or newer). File ▸ Open ▸ this folder, let Gradle sync (needs internet once).
2. Run on a phone/emulator (minSdk 24).
3. Open the app ▸ "Enable keyboard" ▸ tick বাংলা কিবোর্ড ▸ "Switch to this keyboard".
4. Release APK/AAB: Build ▸ Generate Signed Bundle / APK.

## Features
- Layouts: Avro phonetic, Bijoy, Windows Bangla (InScript-based), English QWERTY, symbols. Pick/enable in Settings; 🌐 key cycles, long-press 🌐/space = system IME picker.
- 5 built-in themes + custom themes (colours, gallery image, or free AI-generated image via Pollinations.ai).
- Suggestions: completion, next-word (bigrams), spell correction (edit distance), "＋ word" chip saves new words, My dictionary in Settings. Learns from what you type.
- Live Bangla <-> English translation chip (ML Kit, on-device). Toggle: ⋮ ▸ 🌐 A⇄অ.
- Gboard-style extras: long-press alternates, space-bar swipe moves cursor, hold-delete, double-space = "।", clipboard history, emoji panel, voice typing, one-handed mode, auto-capitalise (English), shift / caps-lock, number/phone fields open symbols, no clipboard/suggestions in password fields.

## Things to verify / extend
- Bijoy and Windows tables are in `Layouts.kt` as plain strings – compare with your muscle memory and edit.
- Avro: `o` is the inherent vowel (`bhalo` -> ভাল, `bhalObashi` -> ভালোবাশি); the suggestion chips help finish words.
- `assets/words.txt` and `bigrams.txt` are tiny seed lists. Replace with a big Bangla frequency list for real-world quality.
- Not included: glide/swipe typing, Avro roman-word dictionary autocorrect, emoji search, stickers/GIFs.
