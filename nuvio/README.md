# Adaptateur Nuvio français

Ce dossier conserve une compatibilité séparée pour les applications Nuvio. Les providers Nuvio sont des modules JavaScript/Stremio et ne sont pas mélangés aux plugins CloudStream `.cs3` du build Gradle principal.

## Addon Snixi compatible

Le sous-dossier courant contient le manifest et le serveur de providers français inspirés de `Snixi92/nuvio-french-providers`. Pour un déploiement serveur, publier le contenu de `nuvio/` sur un hébergeur Node.js et utiliser l’URL publique suivante :

```text
https://<hébergement>/manifest.json
```

Pour un plugin Nuvio Mobile, l’application peut charger le manifest depuis une URL GitHub brute après publication du dépôt.

## Providers inclus

| Provider | Films | Séries | Fonction |
|---|---:|---:|---|
| Frenchstream | Oui | Oui | Recherche d’identifiant TMDB et flux français |
| Movix | Oui | Oui | Fallbacks FStream/Wiflix/Cpasmal et résolution des flux |
| Nakios | Oui | Oui | Provider français complémentaire |
| Purstream | Oui | Oui | Provider français complémentaire |
| ToFlix | Oui | Oui | Provider français complémentaire |
| Nakastream | Oui | Oui | Provider français complémentaire |
| Vstream | Oui | Oui | Provider français complémentaire |

Le sous-dossier `nuvio/gowaru/` contient en plus les providers JS publics de Gowaru, notamment Anime-Sama, AnimeVostFR, AnimeUltra, French Anime, French Manga, FrenchStream, Movix, OtakuFR, Vostfree, WookaFR et d’autres providers français. Ils restent séparés de l’addon Snixi afin d’éviter les collisions de manifest et de noms.

## Vérifications

Les fichiers JavaScript sont contrôlés syntaxiquement dans le CI. Les appels réseau sont protégés par des délais, les liens invalides sont ignorés et les flux sont dédoublonnés avant d’être retournés à Nuvio.

## Sources

- `https://github.com/Snixi92/nuvio-french-providers`
- `https://github.com/Gowaru/gowaru-nuvio-providers`
