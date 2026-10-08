# Kelp Server

The server behind [Kelp](https://github.com/KelpSquid/kelp) and [Squid](https://github.com/KelpSquid/squid):
accounts, capes, emblems, badges, profiles, sharing, friends and messages, the Parent Portal, the Squid Count
leaderboard and the website. One small Java program with no outside libraries.

## What it does

- **Signing in** with Minecraft's own proof (the way game servers check players): the player's Minecraft token never
  leaves their computer. The first time, players give their birth year (not kept) and region, and get a recovery code.
- **Age rules by region**: below the age of digital consent (13 in the US, 13 to 16 in the EU, by country) everything
  social starts off until a parent approves; 13 to 17 starts on and a parent can manage it; UK teens start private.
- **Capes, emblems, badges**: what Squid shows on other players. The emblem and cape unlocks are checked here too.
- **Sharing**: screenshots, clips and custom capes are only public after the admin approves them. Reports go to the
  same queue.
- **Friends and messages**: friends only, both allowed to message, every message filtered (no swearing, phone
  numbers, emails, addresses or links), blocks work both ways.
- **Parent Portal**: approve by an emailed link, sign in by emailed link, switch features, see who the kid messages
  (never what they say), delete the account.
- **Deleting**: an account and everything in it, by the player or their parent.
- **Recovery**: with the old account's recovery code, the admin moves everything to a new Minecraft account.

## Building and testing

```
java tools/Build.java test     builds build/kelp-server.jar after running the end-to-end test
java -jar build/kelp-server.jar data
```

See [DEPLOY.md](DEPLOY.md) for putting it online.
