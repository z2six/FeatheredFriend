![Feathered Friend banner](https://media.forgecdn.net/attachments/description/null/description_d99b0f3c-59bb-4b67-b6bb-a0fdfe0f8076.png)

**Feathered Friend** is a roleplay-focused communication overhaul that replaces boring vanilla chat with something far more flavorful: trained ravens, wax-sealed scrolls, and unique personal sigils.

---

## What is Feathered Friend?

Instead of typing into vanilla chat, you communicate by writing scrolls, sealing them with wax and your personal sigil, and sending them off via your trained raven.  
By default, **vanilla chat is disabled** (server-side config can re-enable it if desired).

The goal is to provide a **simple, immersive, and meaningful** way to talk to other players that fits perfectly into medieval / fantasy / roleplay worlds.

---

## How it works – from wild raven to trusted courier

1. **Find a Raven**  
   Explore the world and locate a wild raven. They’re cautious and clever, so don’t expect them to be instantly friendly.

2. **Tame the Raven**  
   Ravens love Golden Nuggets, but they’re not all the same.  
   Each raven has its own personality – some tame easily, others are stubborn.  
   Listen to its cawing to figure out how much it wants from you.

3. **Craft your Seal Stamp & forge your Sigil**  
   Craft your **Seal Stamp**, then carve a **completely unique sigil** into it.  
   Your sigil is generated from a secret passphrase and stored securely using **SHA-256**.  
   No other player can legitimately reproduce your sigil.

4. **Write and seal your Scroll**  
   Craft a scroll (**2x paper vertically**) and write your message on it.  
   When you’re done, use wax and your Seal Stamp to create a **Sealed Scroll** that bears your personal sigil.

5. **Summon your Raven**  
   Whistle for your raven to come to you. Once it’s by your side, hold the **Sealed Scroll** in your hand.

6. **Send your message**  
   Right-click your raven while holding the Sealed Scroll.  
   The raven immediately takes off and delivers the message to the target player – even if **the sender and/or recipient are offline**.  
   The delivery system is handled entirely server-side and stored in world data.

---

## Sigils, Seal Stamps & Identity

Your sigil is your in-world identity. It can’t be copied, forged, or reliably brute-forced by other players:

- Sigils are derived from a **secret passphrase** → turned into a SHA-256 key → used as a **seed** for generation.
- Sigils are composed from **Seed, Style, and Etchings**, giving you endless combinations.
- The result: practical in-world “signatures” that are **visually unique** and **cryptographically safe** to use on scrolls.

Some example sigils:

|  |  |
|---|---|
| ![Sigil example 1](https://media.forgecdn.net/attachments/description/null/description_46b03b19-5da6-4724-9d41-ec220a578761.png) | ![Sigil example 2](https://media.forgecdn.net/attachments/description/null/description_8db1ccd6-c4d2-4136-9210-92a6956ce55c.png) |
| ![Sigil example 3](https://media.forgecdn.net/attachments/description/null/description_c8ea1f85-2ab7-40ab-be09-631ebb20844e.png) | ![Sigil example 4](https://media.forgecdn.net/attachments/description/null/description_bde1ecbf-af2a-4a16-bea6-38aac097adca.png) |

---

## Scrolls & Writing Interface

To send a message, you’ll first craft and write your scroll:

- **Crafting**: 2x Paper placed vertically in a crafting grid → basic scroll.
- **Writing**: Open the scroll UI and write your message exactly as you want it delivered.
- **Sealing**: Apply wax + your Seal Stamp to bind the contents and imprint your sigil.

![Scroll writing UI](https://media.forgecdn.net/attachments/description/null/description_ce4bbd53-86cf-405e-b364-e3494f19a15c.png)

---

## Custom Date & Calendar System

Feathered Friend also ships with a **fully customizable in-game date system**.  
By default, your world starts on:

**Day 1 of Dawnroot, 1 A.N.**  
*(Year 1 After Notch 😉)*

- Day / month / year names are configurable.
- Perfect for lore-rich servers that want their own in-world calendar.
- Pairs naturally with written correspondence and long-lasting roleplay campaigns.

---

## Raven AI & Behavior

Ravens aren’t just re-skinned parrots – they’re built to feel smart, cautious, and a little bit supernatural:

- **Custom A\* Pathfinding**  
  Ravens use a custom A\* implementation tuned to keep them from getting stuck on terrain.  
  If a raven *does* get into trouble, it will gracefully **teleport nearby or further along its path** rather than hanging in place forever.

- **Hard to Kill**  
  Ravens have a **75% chance to dodge incoming damage**.  
  When they’re hurt or feel threatened, they will **teleport away** to safety and try to avoid further contact.

- **Eyes and Ears Everywhere**  
  Ravens are extremely perceptive. They “see” and “hear” players around them and will **flee as soon as they detect danger**.  
  They hear and see everything. 😉

---

## Delivery System & Multiplayer Support

- **Server-side Delivery**  
  Raven deliveries are **fully independent of any specific player or entity**.  
  A central delivery system runs on the server and is stored in world data.

- **Works with Offline Players**  
  You can send scrolls to both **online and offline** players.  
  Once someone has logged in at least once, they are remembered as a **known player** and can receive ravens anytime.

- **Player Cache**  
  Known players are cached so you can keep sending messages without needing them online every time.

- **Configurable Vanilla Chat**  
  By default, vanilla chat is turned off to encourage raven-based communication.  
  Server admins can re-enable vanilla chat in the config if needed.

---

## Planned & Ongoing Work

**Todo / Roadmap**

- General bug fixing (please report any issues you find!)
- More polish for UI, particles, sounds, and raven behavior
- Additional sigil **Styles** and variations
- More / better sound effects for ravens, seals, and scroll handling
- After a period of stabilization and polishing:
    - Port to all NeoForge 1.21.x versions
    - Port to Forge 1.20.1

---

## Licensing

- **Code:** MIT License
- **Assets (textures, models, sounds, etc.):** **ALL RIGHTS RESERVED** by Z2SIX

You are free to read, modify, and build on the **code** under the terms of the MIT license.  
However, **any changes to or redistribution of the assets outside this mod** is **not allowed under any circumstances**.

---

## Distribution & Modpacks

You may include **Feathered Friend** in modpacks and on servers as long as:

- The mod’s JAR and assets remain **unmodified** inside the pack.
- You do not extract or reuse the art / audio assets outside of this mod.

If you enjoy the mod, a link back to this page is always appreciated. 🖋️🕊️
