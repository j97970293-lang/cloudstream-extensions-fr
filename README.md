# FrenchHub — catalogue TMDB français commun pour CloudStream

FrenchHub est une **seule extension CloudStream**. Son catalogue n’est pas celui de French-Stream, de Movix ou d’un autre site. Les cartes, les fiches, les affiches, les séries et les épisodes proviennent d’un catalogue TMDB centralisé; les sites de streaming ne sont sollicités qu’au moment où l’utilisateur ouvre un film ou lance un épisode.

## Installation

Ajoute ce dépôt dans CloudStream :

```text
https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/repo.json
```

Après la mise à jour, une seule extension doit apparaître : **FrenchHub**. La version publiée du plugin doit être supérieure à l’ancienne afin que CloudStream remplace réellement le fichier déjà installé.

## Fonctionnement du catalogue commun

La page d’accueil et la recherche utilisent les identifiants TMDB. Une fiche de film est représentée par son `tmdbId`; une fiche de série contient les saisons et les épisodes TMDB; chaque épisode conserve son numéro de saison, son numéro d’épisode, son titre et sa date.

Lorsqu’un épisode est lancé, FrenchHub prépare un payload commun contenant notamment les éléments suivants :

| Donnée commune | Utilisation |
|---|---|
| Identifiant TMDB | Recherche directe des sources Movix et Frembed, lorsqu’elles le permettent. |
| Identifiant IMDb | Fallbacks et providers qui indexent leurs contenus avec IMDb. |
| Titre normalisé | Recherche dans French-Stream, Wiflix, French Anime et les autres providers par nom. |
| Année | Désambiguïsation des remakes et titres homonymes. |
| Saison et épisode | Résolution de l’épisode exact, sans mélanger les épisodes d’une autre saison. |

FrenchHub exécute ensuite les providers activés en parallèle. Les liens vidéo et les sous-titres sont rassemblés dans les sources de CloudStream, les URLs identiques étant dédupliquées. L’utilisateur doit donc voir la fiche TMDB commune, ses épisodes communs, puis les lecteurs disponibles de Frembed, Movix, French-Stream, Wiflix et des autres providers qui trouvent effectivement une source.

## Providers et réglages

Les réglages permettent d’activer ou désactiver individuellement les providers internes. Les providers TMDB directs sont privilégiés lorsqu’ils supportent l’identifiant : Frembed reçoit directement le TMDB ID, le type et l’épisode; Movix reçoit directement le chemin TMDB correspondant. Les autres providers utilisent une recherche par titre, puis FrenchHub récupère la donnée de lecture de la fiche correspondante.

| Provider interne | Rôle dans le catalogue commun |
|---|---|
| Frembed | Résolution directe par TMDB ID, saison et épisode. |
| Movix | Résolution directe par TMDB ID, avec ses fallbacks intégrés. |
| French-Stream | Recherche de la fiche française puis extraction de ses hosters. |
| Wiflix | Recherche de la fiche puis résolution de son payload. |
| French Anime, FS Mirror, JourFilm, DoTriv et French-Manga | Fallbacks par recherche lorsqu’un titre et un épisode correspondants sont trouvés. |

Un provider désactivé ne participe pas à la recherche des lecteurs. Les lecteurs ne sont jamais garantis : ils dépendent de la disponibilité réelle du domaine, de Cloudflare et des hosters distants.

## Mise à jour importante après l’ancienne erreur

L’ancienne version utilisait des URLs internes `frenchhub://...`, qui pouvaient être réécrites par CloudStream avant l’appel de `load()`. FrenchHub utilise maintenant des URLs HTTPS internes commençant par `https://frenchhub.local`, qui correspondent au `mainUrl` du MetaProvider et permettent à CloudStream d’identifier correctement FrenchHub. Le catalogue TMDB ne dépend donc plus d’une URL de fiche French-Stream pour ouvrir un titre.

Après l’installation du dépôt, force la mise à jour de FrenchHub ou désinstalle l’ancienne version avant de la réinstaller. Vérifie que la version affichée est **2** ou supérieure.

## Compatibilité Nuvio

Le dossier [`nuvio/`](nuvio/) reste un addon séparé. Son manifest est disponible ici :

```text
https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/nuvio/manifest.json
```

Le serveur Node.js Nuvio doit être déployé séparément sur un hébergement public; GitHub ne l’exécute pas automatiquement.

## Développement

```bash
./gradlew :FrenchHub:make makePluginsJson
```

Le workflow GitHub compile un seul fichier `FrenchHub.cs3`, vérifie syntaxiquement les providers JavaScript Nuvio et publie `plugins.json`, `repo.json` et `FrenchHub.cs3` dans la branche `builds`.

## Limites connues

Les providers de sites n’utilisent pas tous les mêmes identifiants. Lorsqu’un provider ne supporte pas TMDB ou IMDb directement, FrenchHub doit rechercher le titre sur son site; si le titre, l’année ou l’épisode ne correspondent pas, ce provider ne produira aucun lecteur. Cette absence n’empêche pas les autres providers de retourner leurs sources.
