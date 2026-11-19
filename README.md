# Tempo

Android app that holds back WhatsApp notifications from people you pick.

- **Match their time** — when you message them, Tempo starts a timer. Their first reply stops it. Everything they send is then held for as long as they made you wait (from their first reply), and shown together.
- **Fixed delay** — every run of messages from them is held for a set time.

Opening their chat in WhatsApp drops anything held, since you've seen it.

## Install

Every push to `main` builds `Tempo.apk` and attaches it to the **latest** release. Open the Releases page on your phone and install it.

## Setup on the phone

1. Notification access → turn on Tempo.
2. Accessibility → Tempo. If it's greyed out: Settings → Apps → Tempo → ⋮ → **Allow restricted settings**, then try again.
3. Allow notifications.
4. Alarms & reminders → allow (so held messages appear on time).
5. In WhatsApp, for each picked contact: Notifications → Custom notifications → tone **None**, vibrate **Off**, high priority **Off**. Tempo plays the sound when it shows the message.

## Limits

- The message still reaches WhatsApp at once; if you open WhatsApp you'll see it in the chat list.
- They still see delivered (grey) ticks as usual.
- Voice notes you send aren't detected as "you messaged them" — use the *I just messaged them* button.
- Send detection relies on WhatsApp's internal screen ids; a WhatsApp update can break it.
- 1-to-1 chats only, not groups.
