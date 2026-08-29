# DeFE CloudStream Extension

CloudStream provider for **downloadeverythingfromeverywhere.com** — a
meta-search that fans a title out to ~24 download sites and returns direct
file links (mkv/mp4 WEB-DL, BluRay, x264/x265, 480p→2160p).

- **Repo URL**: `https://raw.githubusercontent.com/kgicao29-ux/DeFE/master/repo.json`
- **Artifact**: `release/DeFE.cs3` + matched `release/plugins.json`

## How it works (reverse-engineered)

| Layer | Endpoint |
|---|---|
| Catalog sections | TMDB v3 (`/trending/*`, popular, top rated, on the air) with the site's public bearer token from its JS bundle |
| Search | TMDB `/search/multi`, falling back to `api.tenrai.org/v1/anime?q=` (Jikan shapes) |
| Detail | TMDB `/movie/{id}` / `/tv/{id}` (+seasons); anime via tenrai `/anime/{id}` |
| **Links** | `POST https://slave.downloadeverythingfromeverywhere.com/` with headers `Accept: application/x-ndjson` + `X-Defe-Manual: 1` and body `{mode:"movie"\|"episode", title, year, season?, episode?, tmdb_id\|mal_id, imdb_id}` → NDJSON event stream; `{t:"hit", site, links:[{url,tags,name}]}` events carry the results |

Only **directly downloadable URLs** are emitted as `ExtractorLink`s (VIDEO):
`.mkv/.mp4/.avi/.webm/.m3u8/.ts`, fzmovies `/res/` CDN paths and highxhd
`new_download.php` endpoints. Telegram/page-only results are dropped (not
playable in-app). Quality is derived from the tags (`2160p → 4K`, etc.).

The NDJSON stream is fully buffered (the slave closes the connection when
done, ~10–40 s); CloudStream shows the loading spinner meanwhile. If the
slave answers `429`/`busy`, loadLinks returns no results — just retry.

## Structure

```
DeFE/src/main/kotlin/com/defe/cloudstream/
  DeFEPlugin.kt    — entry
  DeFEProvider.kt  — catalog/search/detail + slave link resolver
release/           — DeFE.cs3 + plugins.json (matched pair)
.github/workflows/build.yml — optional CI publishing to `builds`
```

## Build

```bash
./gradlew --no-daemon make makePluginsJson
```
