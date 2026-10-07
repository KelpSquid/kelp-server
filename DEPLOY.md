# Putting the server online

The plan: a small rented Linux server (about $5 a month) runs kelp-server.jar, and Cloudflare sits in front of it
for HTTPS and protection. A grown-up has to sign up for both (they need a payment card).

## 1. The server

1. Rent the smallest Linux server (Ubuntu) at a host like Hetzner. Note its IP address.
2. Install Java 21: `sudo apt install openjdk-21-jre-headless`
3. Make a user and folder: `sudo useradd -m kelp`, then copy `kelp-server.jar` to `/home/kelp/`.
4. Make `/home/kelp/data/server.properties` from `server.properties.example`.
5. Run it as a service, so it starts by itself: `/etc/systemd/system/kelp.service`:

```
[Unit]
Description=Kelp server
After=network.target

[Service]
User=kelp
WorkingDirectory=/home/kelp
ExecStart=/usr/bin/java -Xmx512m -Djava.awt.headless=true -jar kelp-server.jar data
Restart=always

[Install]
WantedBy=multi-user.target
```

   then `sudo systemctl enable --now kelp`.

## 2. Cloudflare in front

1. Add kelplauncher.org to Cloudflare and point an `A` record at the server's IP, with the orange cloud (proxied) on.
2. SSL/TLS: "Full". Cloudflare gives visitors HTTPS.
3. Put a small proxy (Caddy) on the server listening on port 443 with a Cloudflare Origin Certificate, passing to
   port 8080, and close every other port with the firewall (`ufw allow 22,443/tcp`). Only Cloudflare can reach it.

## 3. Backups

Everything is in `/home/kelp/data`. Copy it somewhere else every night (for example with `rsync` or the host's
backup option). To restore, put the folder back and restart the service.
