# Audit initial des sources Luna

## Dépôts transmis

- `https://git.luna-app.eu/50n50/sources` est un vaste catalogue JavaScript de sources Sora/Luna. La racine liste de nombreux providers internationaux ; les sources françaises pertinentes doivent être identifiées par recherche de code et par inspection de leurs manifests.
- `https://git.luna-app.eu/MXFia19/sources` contient notamment `anime-sama`, `movix`, `voir-anime`, `bingebox`, `cinepulse`, `nakanime`, `Nakastream`, `purstream` et `scan-sama`. Le dépôt affiche des changements récents sur Movix, Anime-Sama et Voir-Anime.

## Hypothèse de travail

Les modules Luna sont écrits en JavaScript et ne peuvent pas être inclus tels quels dans une extension CloudStream Kotlin. Seuls les providers dont la recherche, le matching TMDB/Cinemeta et la résolution des liens sont techniquement transposables seront retenus. Leur ajout dépendra aussi de l’absence de doublon fonctionnel avec les providers FrenchHub déjà actifs.

## Résultats du clonage de lecture

- Le catalogue `50n50` ne révèle pas, par son nom de dossier, de provider français vidéo évident ; les répertoires relevés comprennent notamment `donghuastream`, `hdfilme`, `mangafreak` et `otaku-streamers`.
- Le catalogue `MXFia19` contient plusieurs candidats francophones explicites : `anime-sama`, `voir-anime`, `movix`, `bingebox`, `cinepulse`, `nakanime`, `Nakastream`, `nakios` et `purstream`.
- `movix` et `purstream` se recoupent déjà avec le provider Movix actif de FrenchHub. L’audit détaillé doit en priorité départager les candidats qui apportent une source nouvelle : Voir-Anime, BingeBox, CinePulse, NakaStream, Nakios et Anime-Sama.

## Évaluation technique des candidats

| Source | Périmètre | Évaluation pour FrenchHub |
|---|---|---|
| NakaStream | Films et séries, API REST `nakastream.tv/api/v1`, HLS et sous-titres | Candidat retenu : flux direct, API lisible, en-têtes `Origin`/`Referer` simples et matching TMDB utilisable. |
| Nakios | Films, séries et anime, API/URL dynamiques | Non retenu pour ce correctif : détection de domaine dynamique, proxy récursif et collecte générique de liens, donc plus fragile. |
| BingeBox | Agrégateur multi-sources | Non retenu : agrège déjà de nombreux serveurs et risquerait de recréer les doublons signalés. |
| CinePulse | Films, séries et anime, API authentifiée | Non retenu : dépend de tokens rotatifs, réglages secrets et stockage Supabase externe. |
| Voir-Anime | Anime, extraction HTML multi-hébergeurs | Non retenu : dépend de nombreux extracteurs fragiles et subit des blocages Cloudflare. |
| Movix / Purstream | Contenus français | Déjà couverts fonctionnellement par le provider Movix présent dans FrenchHub. |

### Contrat NakaStream à transposer

La recherche interroge `GET https://nakastream.tv/api/v1/browse/catalog?page=1&limit=20&sort=recent&search=<titre>`. Les flux proviennent de `GET https://nakastream.tv/api/v1/streaming/sources/<id>?type=movie` ou `type=tv&season=<saison>&episode=<épisode>` avec les en-têtes `Origin: https://nakastream.tv` et `Referer` correspondant à la fiche. Les objets `sources` contiennent des URLs directes et peuvent contenir un tableau `subtitles`.

## Anomalie signalée

La capture reçue montre la répétition du libellé `VF 360p 360p` dans la fenêtre de lecteurs. La correction doit dédupliquer sur une empreinte plus robuste que l’URL brute et le nom de provider : URL finale normalisée, hôte, chemin, qualité, langue et libellé de serveur.
