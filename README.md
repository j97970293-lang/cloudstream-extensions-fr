# CloudStream Extensions FR

Dépôt public d’extensions CloudStream françaises reconstruites à partir de providers et d’extracteurs publics. Le code est organisé en modules Kotlin indépendants afin que chaque provider puisse être testé, corrigé et publié séparément.

## Dépôt CloudStream

Dans CloudStream, ajoute le manifest suivant :

```text
https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/repo.json
```

CloudStream lira ensuite la liste publiée dans la branche `builds` et proposera les fichiers `.cs3` disponibles.

## Modules initiaux

| Module | Fonction |
|---|---|
| FrenchStreamProvider | Catalogue French-Stream, recherche, films, séries, saisons et lecteurs multiples |
| Movix | Catalogue TMDB, recherche, films, séries, Frembed et providers Movix |
| FSTV | Provider complémentaire French-Stream TV |
| FrenchManga | Provider français complémentaire |
| WiflixProvider | Films et séries Wiflix |
| Frembed | Résolution de liens Frembed |
| FrenchAnime | Catalogue FrenchAnime |
| FsMirrorLol | Miroir French-Stream |
| JourFilm | Provider 1 Jour 1 Film |
| DoTriv | Provider DoTriv |

Les modules seront marqués comme opérationnels, lents ou indisponibles selon les tests réseau réels. Un provider qui renvoie une erreur HTTP ne doit pas empêcher les autres plugins de se compiler ou de se charger.

## Lecteurs et extracteurs

Les providers distinguent toujours le catalogue, la fiche, les épisodes et la résolution des liens. Pour un film, une seule fiche est exposée; les lecteurs sont ensuite résolus par `loadLinks`. Les séries exposent un épisode par numéro réel, puis les hosters disponibles pour cet épisode. Les extracteurs communs privilégient les liens HLS/MP4 réels, ajoutent les headers et referers nécessaires et ignorent les réponses HTML ou JSON invalides.

## Compatibilité Nuvio

Les providers Nuvio utilisent un format JavaScript différent des plugins CloudStream `.cs3`. Un adaptateur séparé sera conservé dans le dépôt sous `nuvio/` lorsque le provider possède une implémentation Nuvio fiable; il ne sera pas mélangé au build Gradle CloudStream.

## Build local

```bash
./gradlew make
./gradlew makePluginsJson
```

Pour compiler un module précis :

```bash
./gradlew :FrenchStreamProvider:make
./gradlew :Movix:make
```

## Références de conception

Le dépôt s’inspire de `Nikola17/cloudstream-frenchstream`, de la structure de publication Phisher/Megix et des providers français publics référencés dans le dossier de documentation. Les implémentations sont réécrites ou adaptées; les artefacts `.cs3` déjà cassés ne sont pas considérés comme une correction source.
