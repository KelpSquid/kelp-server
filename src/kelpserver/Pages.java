package kelpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static kelpserver.Http.html;

/**
 * The website: the home page, player profiles, the leaderboard, the privacy page, the Parent Portal and the admin
 * page. Plain HTML with a little script, in the same deep-sea colors as Kelp, and readable on a phone.
 */
public final class Pages {
    private final Players players;
    private final Store store;

    public Pages(Players players, Store store) {
        this.players = players;
        this.store = store;
    }

    public void routes(Http.Router r) {
        r.add("GET /", req -> Http.Response.html(page("Kelp", """
                <h1>Kelp</h1>
                <p class="big">A Minecraft launcher with its own mod loader, Squid. Easy for everyone, endless for makers.</p>
                <ul>
                  <li>Mods from the Squid Store in one click, or make your own in five minutes</li>
                  <li>Skins, capes and effects everyone with Squid can see</li>
                  <li>Skate 3 style replays, instant clips, and your own emblem</li>
                  <li>120 languages, world backups, and Parent Controls</li>
                </ul>
                <p><a class="button" href="https://github.com/KelpSquid/kelp/releases/latest">Download Kelp</a></p>
                <p><a href="/leaderboard">Squid Count leaderboard</a> &middot; <a href="/parents">Parent Portal</a> &middot; <a href="/privacy">Privacy</a></p>
                """)));
        r.add("GET /u/{name}", req -> {
            Map<String, Object> p;
            try {
                p = players.profile(req.value("name"), null);
            } catch (Http.Problem e) {
                return Http.Response.html(page("No such player", "<h1>No player called that</h1><p><a href=\"/\">Home</a></p>"));
            }
            StringBuilder body = new StringBuilder();
            body.append("<div class=\"card\">");
            if (p.get("emblem") != null && p.get("uuid") != null) {
                body.append("<img class=\"emblem\" alt=\"\" src=\"/api/players/").append(html((String) p.get("uuid"))).append("/emblem.png\">");
            }
            body.append("<h1>").append(html((String) p.get("name"))).append(badges(p)).append("</h1>");
            if (Boolean.TRUE.equals(p.get("public"))) {
                body.append("<p>Squid Count: <b>").append(p.get("squidCount")).append("</b></p>");
                body.append("<p class=\"dim\">Playing since ").append(date(p.get("since"))).append("</p>");
            } else {
                body.append("<p class=\"dim\">This profile is private.</p>");
            }
            body.append("</div>");
            if (Boolean.TRUE.equals(p.get("public"))) {
                body.append("<h2>Shared</h2><div id=\"posts\" class=\"posts\"></div><script>")
                        .append("fetch('/api/players/").append(html(req.value("name"))).append("/posts').then(r=>r.json()).then(list=>{")
                        .append("const box=document.getElementById('posts');if(!list.length){box.textContent='Nothing shared yet.';return;}")
                        .append("for(const p of list){const a=document.createElement('a');a.href='/api/posts/'+p.id+'/file';")
                        .append("if(p.kind==='screenshot'){const i=document.createElement('img');i.src=a.href;i.alt=p.title||'';a.appendChild(i);}")
                        .append("else{a.textContent='\\u25B6 '+(p.title||'Clip');a.className='clip';}box.appendChild(a);}});</script>");
            }
            return Http.Response.html(page(html((String) p.get("name")) + " on Kelp", body.toString()));
        });
        r.add("GET /leaderboard", req -> {
            StringBuilder rows = new StringBuilder();
            for (Map<String, Object> row : players.leaderboard(100)) {
                rows.append("<tr><td>").append(row.get("rank")).append("</td><td><a href=\"/u/").append(html((String) row.get("name"))).append("\">")
                        .append(html((String) row.get("name"))).append("</a>").append(badges(row)).append("</td><td>").append(row.get("squidCount")).append("</td></tr>");
            }
            return Http.Response.html(page("Squid Count leaderboard", "<h1>Squid Count</h1><p class=\"dim\">Points for advancements, like gamerscore. "
                    + "Only public profiles are listed.</p><table><tr><th>#</th><th>Player</th><th>Points</th></tr>" + rows + "</table>"));
        });
        r.add("GET /privacy", req -> Http.Response.html(page("Privacy", """
                <h1>Privacy, in plain words</h1>
                <p>Kelp keeps as little about you as it can.</p>
                <ul>
                  <li><b>Your Minecraft name and ID</b>, to know it's you. We never see your password or your Minecraft token.</li>
                  <li><b>Your region and age group</b> (child, teen or adult), to follow the law. We ask your birth year once and don't keep it.</li>
                  <li><b>What you choose to share</b>: your emblem, cape, Squid Count, and screenshots or clips (only public after we check them).</li>
                  <li><b>Messages</b> between friends, kept so reported ones can be checked. Parents can see who their kid talks to, never what they say.</li>
                  <li><b>A parent's email</b>, only if a parent links themselves, to send them sign-in links.</li>
                </ul>
                <p>Kids below their region's age of digital consent have everything social off until a parent turns it on.</p>
                <p>Delete your account any time in Kelp (Accounts &gt; Delete Data), or a parent can in the Parent Portal: everything about you is deleted.</p>
                """)));
        r.add("GET /parents", req -> Http.Response.html(page("Parent Portal", """
                <h1>Parent Portal</h1>
                <p>Manage your kid's Kelp account: choose what they can do, see who they message, or delete the account.</p>
                <form id="f"><input id="email" type="email" placeholder="Your email" required> <button>Send me a sign-in link</button></form>
                <p id="msg" class="dim"></p>
                <script>
                document.getElementById('f').onsubmit=async e=>{e.preventDefault();
                  await fetch('/api/parents/login',{method:'POST',body:JSON.stringify({email:document.getElementById('email').value})});
                  document.getElementById('msg').textContent='If that email manages a Kelp account, a sign-in link is on its way.';};
                </script>
                """)));
        r.add("GET /parents/approve", req -> Http.Response.html(page("Approve", """
                <h1>Approve your kid's account</h1><div id="box" class="dim">Loading...</div>
                <script>
                const token=location.hash.slice(1);
                const names={profile:'A public profile page',posting:'Sharing screenshots and clips (we check each one first)',
                  comments:'Reactions on posts',dms:'Friends and messages (friends only, filtered)',multiplayer:'Playing on servers',
                  chat:'In-game chat',voice:'Voice chat'};
                fetch('/api/parents/link/'+token).then(r=>r.json()).then(d=>{
                  const box=document.getElementById('box');box.className='';
                  if(d.error){box.textContent=d.error;return;}
                  let h='<p><b>'+d.name.replace(/[<&]/g,'')+'</b> asked you to manage their Kelp account. Choose what to allow:</p>';
                  for(const k in names){h+='<label><input type="checkbox" id="'+k+'"'+(d.social[k]?' checked':'')+'> '+names[k]+'</label><br>';}
                  h+='<p><button id="ok">Approve</button></p>';box.innerHTML=h;
                  document.getElementById('ok').onclick=async()=>{const social={};for(const k in names)social[k]=document.getElementById(k).checked;
                    const r=await fetch('/api/parents/link/'+token+'/approve',{method:'POST',body:JSON.stringify({social})});const j=await r.json();
                    if(j.session){location.href='/parents/portal#'+j.session;}else{box.textContent=j.error||'Something went wrong.';}};});
                </script>
                """)));
        r.add("GET /parents/portal", req -> Http.Response.html(page("Parent Portal", """
                <h1>Parent Portal</h1><div id="kids" class="dim">Loading...</div>
                <script>
                const token=location.hash.slice(1);const auth={headers:{Authorization:'Bearer '+token}};
                const names={profile:'Public profile',posting:'Sharing',comments:'Reactions',dms:'Friends and messages',multiplayer:'Servers',chat:'Chat',voice:'Voice chat'};
                function esc(s){return String(s).replace(/[<&"]/g,c=>({'<':'&lt;','&':'&amp;','"':'&quot;'}[c]));}
                async function load(){const r=await fetch('/api/parents/children',auth);const kids=await r.json();const box=document.getElementById('kids');box.className='';
                  if(kids.error){box.textContent=kids.error;return;}
                  let h='';for(const k of kids){h+='<div class="card"><h2>'+esc(k.name)+'</h2>';
                    for(const f in names)h+='<label><input type="checkbox" data-kid="'+k.uuid+'" data-f="'+f+'"'+(k.social[f]?' checked':'')+'> '+names[f]+'</label><br>';
                    h+='<h3>Who they message</h3>'+(k.contacts.length?'<ul>'+k.contacts.map(c=>'<li>'+esc(c.name)+'</li>').join('')+'</ul>':'<p class="dim">Nobody yet.</p>');
                    h+='<p><button data-save="'+k.uuid+'">Save</button> <button class="danger" data-del="'+k.uuid+'">Delete this account</button></p></div>';}
                  box.innerHTML=h||'<p>No accounts are linked to this email.</p>';
                  document.querySelectorAll('[data-save]').forEach(b=>b.onclick=async()=>{const id=b.dataset.save;const social={};
                    document.querySelectorAll('[data-kid="'+id+'"]').forEach(c=>social[c.dataset.f]=c.checked);
                    await fetch('/api/parents/children/'+id,{method:'PUT',headers:auth.headers,body:JSON.stringify({social})});b.textContent='Saved';});
                  document.querySelectorAll('[data-del]').forEach(b=>b.onclick=async()=>{if(!confirm('Delete this account and everything in it? This cannot be undone.'))return;
                    await fetch('/api/parents/children/'+b.dataset.del,{method:'DELETE',headers:auth.headers});load();});}
                load();
                </script>
                """)));
        r.add("GET /admin", req -> Http.Response.html(page("Admin", """
                <h1>Admin</h1><p><input id="key" type="password" placeholder="Admin key"> <button id="go">Open the queue</button></p><div id="q"></div>
                <script>
                let key=sessionStorage.getItem('k')||'';document.getElementById('key').value=key;
                function esc(s){return String(s).replace(/[<&"]/g,c=>({'<':'&lt;','&':'&amp;','"':'&quot;'}[c]));}
                async function act(path){await fetch(path,{method:'POST',headers:{'X-Admin-Key':key}});load();}
                async function load(){const r=await fetch('/api/admin/queue',{headers:{'X-Admin-Key':key}});const d=await r.json();const q=document.getElementById('q');
                  if(d.error){q.textContent=d.error;return;}let h='<h2>Posts ('+d.posts.length+')</h2>';
                  for(const p of d.posts)h+='<div class="card">'+esc(p.kind)+': '+esc(p.title)+' <a target="_blank" href="/api/admin/files/'+p.id+'">open</a> '
                    +'<button onclick="act(\\'/api/admin/posts/'+p.id+'/approve\\')">Approve</button> <button class="danger" onclick="act(\\'/api/admin/posts/'+p.id+'/reject\\')">Reject</button></div>';
                  h+='<h2>Capes ('+d.capes.length+')</h2>';
                  for(const c of d.capes)h+='<div class="card"><a target="_blank" href="/api/admin/files/'+c.id+'">open</a> '
                    +'<button onclick="act(\\'/api/admin/capes/'+c.id+'/approve\\')">Approve</button> <button class="danger" onclick="act(\\'/api/admin/capes/'+c.id+'/reject\\')">Reject</button></div>';
                  h+='<h2>Reports ('+d.reports.length+')</h2>';
                  for(const x of d.reports)h+='<div class="card">'+esc(x.kind)+' '+esc(x.target)+': '+esc(x.reason)+' <button onclick="act(\\'/api/admin/reports/'+x.id+'/close\\')">Close</button></div>';
                  q.innerHTML=h;}
                document.getElementById('go').onclick=()=>{key=document.getElementById('key').value;sessionStorage.setItem('k',key);load();};
                if(key)load();
                </script>
                """)));
        r.add("GET /api/health", req -> Http.Response.json(Map.of("ok", true)));
    }

    private static String badges(Map<String, Object> p) {
        StringBuilder out = new StringBuilder();
        Object list = p.get("badges");
        if (list instanceof List<?> l) {
            for (Object b : l) out.append(" <span class=\"badge\" title=\"").append(html(String.valueOf(b))).append("\">").append(badgeIcon(String.valueOf(b))).append("</span>");
        }
        return out.toString();
    }

    private static String badgeIcon(String badge) {
        return switch (badge) {
            case "dev" -> "&#128295;";
            case "beta-tester" -> "&#128208;";
            case "early-player" -> "&#11088;";
            case "github-contributor" -> "&lt;/&gt;";
            case "discord-member" -> "&#128172;";
            case "birthday" -> "&#127874;";
            default -> "";
        };
    }

    private static String date(Object millis) {
        if (!(millis instanceof Number n)) return "";
        return DateTimeFormatter.ofPattern("MMMM d, yyyy").format(Instant.ofEpochMilli(n.longValue()).atZone(ZoneOffset.UTC));
    }

    static String page(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>"
                + ":root{--bg:#0b1633;--card:#13254a;--text:#e8f0ff;--dim:#9fb3d9;--accent:#4fd18b;--danger:#e05a5a}"
                + "body{margin:0;background:linear-gradient(#123a6e,var(--bg) 60%);color:var(--text);font:16px/1.5 system-ui,sans-serif;min-height:100vh}"
                + "main{max-width:760px;margin:0 auto;padding:24px 16px}h1{font-size:2rem;margin:.2em 0}a{color:var(--accent)}"
                + ".big{font-size:1.2rem}.dim{color:var(--dim)}.card{background:var(--card);border-radius:10px;padding:14px 16px;margin:12px 0}"
                + ".button,button{background:var(--accent);color:#06210f;border:0;border-radius:8px;padding:10px 16px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block}"
                + "button.danger{background:var(--danger);color:#fff}input{padding:9px;border-radius:8px;border:1px solid #2c4a80;background:#0f1f40;color:var(--text);max-width:100%}"
                + "table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #22406f;text-align:left}"
                + ".emblem{width:96px;height:96px;image-rendering:pixelated;float:right}.posts{display:flex;flex-wrap:wrap;gap:8px}"
                + ".posts img{width:220px;max-width:100%;border-radius:6px}.clip{display:inline-block;padding:10px;background:var(--card);border-radius:6px}"
                + "label{line-height:2}</style></head><body><main>" + body + "</main></body></html>";
    }
}
