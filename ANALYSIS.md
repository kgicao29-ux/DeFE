# DeFE — reverse-engineering notes (2026-08-29)

## Site

- **downloadeverythingfromeverywhere.com**: Next.js PWA (rolldown chunks),
  Cloudflare. "Free Download Movies, TV Shows & Anime in HD".
- Routes: `/` home, `/m/<tmdb-id>` movie, `/s/<tmdb-id>` series,
  `/a/<mal-id>` anime (via tenrai), `/?q=` search, `/suggest` (site suggestions).
- No server API of its own for content — the client talks to TMDB (catalog
  metadata) and a single **scraper slave** (links).

## The three APIs

### 1. TMDB (catalog + search + detail)
- Client chunk `tmdb-client-*.js` embeds a public v3 bearer token
  (`eyJhbGciOiJIUzI1NiJ9…`, aud 5b10acad…). Standard
  `Authorization: Bearer` against `api.themoviedb.org/3`.
- Used: `/search/multi`, `/trending/movie|tv/week`, `/movie|tv/popular`,
  `/movie|tv/top_rated`, `/movie/now_playing`, `/tv/on_the_air`,
  `/movie|tv/{id}?append_to_response=external_ids`, `/tv/{id}/season/{n}`.
- Images: `https://image.tmdb.org/t/p/w342|w500<path>`.

### 2. tenrai (anime; Jikan mirror)
- `https://api.tenrai.org/v1`: `/anime?q=&limit=&sfk=true`, `/anime/{id}`,
  `/anime/{id}/episodes` (paginated; `pagination.last_visible_page`),
  `/seasons/now`.
- Shapes = Jikan (mal_id, title_english, images.jpg.large_image_url,
  aired.prop.from.year, genres). 429 → Retry-After backoff (client retries ×4).

### 3. slave (the link engine) — `slave.downloadeverythingfromeverywhere.com`
- `POST /` — headers:
  `Content-Type: application/json`, `Accept: application/x-ndjson`,
  `X-Defe-Manual: 1` (manual trigger flag; browser also sends Origin/Referer).
- Body (from `dl-*.js` → `Ee()` + callers):
  - movie page: `{mode:"movie", title, year, tmdb_id, imdb_id}`
  - episode: `{mode:"episode", title, year, season, episode, tmdb_id, imdb_id}`
  - season pack: `{mode:"series", …}`
  - anime: `{tmdb_id: null, mal_id, imdb_id}` instead of tmdb_id
    (+`anime:true` when TMDB item is JP animation).
- Response: NDJSON lines:
  `{t:"start",total}` · `{t:"check"|"miss"|"status",site,done,total}` ·
  `{t:"hit",site,links:[{url,tags:[…],name,release}]}` ·
  `{t:"done",found,total}` · `{t:"busy"}` (429) · `{t:"error",msg}`.
- Link `tags` carry quality (480p/720p/1080p/2160p), codec (x264/x265/10bit),
  source (WEB-DL/BluRay/WEBRip/REMUX), size (e.g. "2.6GB"), audio/lang, and
  `Telegram` for t.me links.
- ~24 source sites per query (111477, phonofilm, moviesdrive, 4khdhub,
  hindmoviez, fzmovies, katmoviehd, moviesmod, filmgo, hdmoviez, multishows,
  cinefy, vadapav, nkiri, subsl, moviebox, filmvault, …). Fan-out takes
  ~10–40 s end-to-end; connection closes after `done`.
- Direct-file URL shapes seen:
  - `https://a.111477.xyz/…​.mkv` (plain file server)
  - fzmovies: `https://<random>.<rotating>.cyou/res/<h1>/<h2>/<h2>/Name.mp4`
  - highxhd: `https://dl1.highxhd.com/new_download.php?id=&st=<sha>&e=<exp>&res=<q>&filename=…`
  - moviesmod: `cloud.unblockedgames.world/?sid=<b64>` (same driveseed chain
    as UHDMovies — resolvable with the bypassHrefli dance if ever needed)

## CloudStream mapping

- Catalog item URL = site URL (`/m/…`, `/s/…`, `/a/…`) → `load()` switches on
  the prefix; `Episode.data` carries the slave request payload as JSON.
- `loadLinks` → one POST, buffer NDJSON (nicehttp `.text`), filter
  direct files, map tags → `Qualities.*`.
- Gotcha for Kotlin: block comments NEST — a KDoc containing the literal
  `/*` (e.g. "/trending/*") breaks the build with "Unclosed comment".
