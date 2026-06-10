# Titan — Mobile Companion

The official native mobile companion for **Titan**, a sovereign AI cognitive
agent with persistent memory, on-chain identity, and metabolic self-governance.

Titan itself lives in the cloud. This app is its **face and senses in your
pocket** — a thin, fully-native client. No model, no private data, and no
cognition runs on the phone.

## What it does
- **Chat** with your Titan, natively.
- **Configure** it — edit settings safely from your phone.
- **Watch over it** — kernel / Guardian / module health, backups, and a
  one-tap restart if something needs it.
- **Be present** — optionally share location & local time so your Titan is
  aware of where its Maker is in the world (every sensor is opt-in).

## Security
Passwordless **QR device-pairing** with a hardware-backed key (Android
Keystore), mutual confirmation, biometric/passcode app lock, and a
private-network (Tailscale) or TLS transport. Your Titan only ever talks to
devices you've explicitly paired.

## Platforms
Android first (Kotlin + Jetpack Compose), built on a Kotlin Multiplatform
core. iOS to follow.

## Architecture
```
 app (Kotlin/Compose)  ──signed HTTPS──▶  TC² Console Agent (VPS)  ──▶  Titan kernel
   shared/  (KMP logic: networking, crypto, models — iOS reuses this)
```

## Note
This is a commit-replay public mirror; internal docs and secrets are filtered
out of every published commit.

🌐 [iamtitan.tech](https://iamtitan.tech)
