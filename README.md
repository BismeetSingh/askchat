# AskChat

Ask questions about your own WhatsApp chats in plain language, on your Android phone:

> *"What's the password I sent to Rahul last week?"*
> *"What was the GST number in the invoice Rahul shared?"*

AskChat imports your chats (and the documents shared in them), indexes them **on the phone**, and uses an LLM
(Claude or OpenAI, your own API key) to understand the question and answer from only the messages that matched.

> Not affiliated with, endorsed by, or sponsored by WhatsApp or Meta. Use it only on your own data.

## Features

- **All chats from WhatsApp's nightly backup** — decrypts `msgstore.db.crypt15` on-device using your 64-digit
  end-to-end backup key. Re-imports automatically when a newer backup exists.
- **Or single chats** via WhatsApp → *Export chat* → share to AskChat (`.txt` / `.zip`, Android & iOS formats).
- **Search inside shared documents** — PDF, Word (`.docx`), Excel (`.xlsx`), PowerPoint (`.pptx`), text/CSV.
- **"Sent by me" vs "received"**, contacts, and date ranges ("last week", "in March") are understood.
- **Answers cite their sources** — tap through to the exact messages.
- **Highlight text anywhere → AskChat** in the text-selection menu to start a question.
- **Claude or OpenAI** — switch in Settings.

## How it works

```
 INGESTION (on device)
   backup / exports / documents ──▶ SQLite: messages (+ doc text chunks) + FTS4 full-text index

 QUERY
   ① PLAN      LLM: question + today's date + chat names  ──▶ {contacts, direction, dates, keywords}
   ② RETRIEVE  phone: SQL filters + full-text search        ──▶ the few matching messages
   ③ ANSWER    LLM: question + those messages only          ──▶ {answer, cited message ids}
```

There are no embeddings. Chat questions are mostly about **who / when / exact values**, so retrieval combines
structured filters with keyword search, and — when the filtered window is small (≤400 messages) — sends the
whole window. That's what finds a password sent as a bare string like `Hunter2@2026`, which neither keyword
nor semantic search would match.

Shared documents are split into ~1,200-character chunks tagged with the document name, sender and date. They
enter the context only through keyword hits (plus neighbouring chunks), so a large PDF can't crowd out messages.

Key files:

| Path | What |
|---|---|
| `app/src/main/java/com/askchat/rag/RagEngine.kt` | Retrieval tiers |
| `app/src/main/java/com/askchat/rag/LlmService.kt` | Prompts + JSON schemas shared by both providers |
| `app/src/main/java/com/askchat/rag/ClaudeService.kt`, `OpenAiService.kt` | Provider calls |
| `app/src/main/java/com/askchat/data/ChatDatabase.kt` | SQLite + FTS4 store |
| `app/src/main/java/com/askchat/data/ChatParser.kt` | Export-file parser |
| `app/src/main/java/com/askchat/backup/Crypt15Decryptor.kt` | Streaming crypt15 decryption |
| `app/src/main/java/com/askchat/backup/MsgstoreReader.kt` | Reads chats/documents from the decrypted DB |
| `app/src/main/java/com/askchat/backup/DocumentText.kt` | Document text extraction + chunking |

## Privacy

- Chats, documents and the index stay on the phone; app data is excluded from Android backups.
- The decrypted backup is written to no-backup storage, read, and deleted immediately.
- The backup key is stored encrypted with an Android Keystore key.
- Per question, the LLM provider receives: the question, your chat names, and the handful of messages
  (or document snippets) that matched locally. That can include sensitive values — that's the point of the
  question — so use a provider you trust with that.
- API keys are stored in app-private preferences (fine for personal use; not hardened for distribution).

## Setup

Requirements: Android 10+ (minSdk 29), Android Studio, an Anthropic or OpenAI API key.

1. Build & install: open in Android Studio and run, or `./gradlew installDebug`.
2. **Settings** → choose Claude or OpenAI → paste your API key → Save.
3. **Chats** → *WhatsApp backup*:
   1. In WhatsApp: Settings → Chats → Chat backup → End-to-end encrypted backup →
      **Use 64-digit encryption key instead**. Save the key, then tap *Back up*.
   2. Paste the 64-digit key in AskChat.
   3. *Choose WhatsApp folder* → `Android/media/com.whatsapp/WhatsApp` → Use this folder.
   4. (Optional) allow Contacts so numbers show as names.
   5. *Sync now*.
4. **Ask** away.

## Limitations

- The backup format and WhatsApp's internal database schema are undocumented and can change with WhatsApp
  updates; the app checks the schema and reports clearly if it's unsupported.
- Only 64-digit-key backups can be decrypted (password-based E2E backups keep the key on WhatsApp's servers).
- Not indexed: photos/screenshots, scanned PDFs without a text layer, password-protected PDFs, legacy
  `.doc`/`.xls`. Documents are capped at ~60 pages / 40k characters.
- Retrieval is keyword-based: paraphrases the planner doesn't anticipate can be missed in large time windows.

## Development

```
./gradlew testDebugUnitTest   # parser, crypt15 decryption round-trips, schemas, document extraction
```

## Acknowledgements

- [wa-crypt-tools](https://github.com/ElDavoo/wa-crypt-tools) (GPL-3.0) — documentation of the crypt15
  file format and key derivation. AskChat's decryptor is an independent Kotlin implementation.
- [WhatsApp-Chat-Exporter](https://github.com/KnugiHK/WhatsApp-Chat-Exporter) (MIT) — reference for the
  `msgstore.db` schema.
- [pdfbox-android](https://github.com/TomRoush/PdfBox-Android), [Bouncy Castle](https://www.bouncycastle.org/),
  and the official [Anthropic](https://github.com/anthropics/anthropic-sdk-java) and
  [OpenAI](https://github.com/openai/openai-java) Java SDKs.

## License

[MIT](LICENSE)
