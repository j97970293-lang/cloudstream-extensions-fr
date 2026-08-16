# FrenchHub — extension CloudStream française fédératrice

FrenchHub est une **seule extension CloudStream** qui regroupe un catalogue français commun et plusieurs sources internes. Les providers ne sont pas publiés comme dix extensions séparées : ils sont embarqués dans le même fichier `FrenchHub.cs3` et interrogés lors de l’ouverture d’un titre.

## Installation CloudStream

Ajoute le dépôt suivant dans CloudStream :

```text
https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/repo.json
```

Après installation, une seule extension doit apparaître : **FrenchHub**. La fiche d’un titre conserve une seule entrée de contenu, puis `loadLinks` interroge les providers activés et affiche leurs lecteurs comme sources vidéo.

## Providers internes

| Provider | Films | Séries | Animés | Réglable |
|---|---:|---:|---:|---:|
| French-Stream | Oui | Oui | Non | Oui |
| Movix | Oui | Oui | Non | Oui |
| FSTV | Oui | Oui | Non | Oui |
| French-Manga | Oui | Oui | Oui | Oui |
| Wiflix | Oui | Oui | Non | Oui |
| Frembed | Oui | Oui | Oui | Oui |
| French Anime | Oui | Oui | Oui | Oui |
| FS Mirror | Oui | Oui | Non | Oui |
| JourFilm | Oui | Oui | Non | Oui |
| DoTriv | Oui | Oui | Non | Oui |

Les réglages de FrenchHub sont accessibles depuis la page de l’extension dans CloudStream. Chaque provider peut être activé ou désactivé individuellement; après l’enregistrement, l’application recharge les sources avec la nouvelle sélection.

## Lecteurs et ordre de résolution

Les liens sont résolus au moment où l’utilisateur ouvre le titre. Frembed est prioritaire lorsqu’il renvoie un lien valide. Movix utilise ensuite ses domaines actifs découverts via `address.json`, les endpoints FStream/Wiflix/J1F lorsqu’ils répondent, et filtre les URLs de test ou les pages manifestement fausses. Les liens d’hosters sont transmis au système d’extracteurs CloudStream afin d’obtenir les flux réels plutôt que d’afficher uniquement une WebView.

## Compatibilité Nuvio

Le dossier `nuvio/` conserve un addon Nuvio compatible basé sur les providers français Snixi, avec des providers Gowaru supplémentaires dans `nuvio/gowaru/`. Le manifest mobile Snixi est disponible ici après publication :

```text
https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/nuvio/manifest.json
```

Le serveur Nuvio n’est pas automatiquement hébergé par GitHub Pages. Pour le mode addon serveur, déploie le dossier `nuvio/` sur un hébergement Node.js, puis utilise l’URL publique de son `manifest.json`.

## Développement

```bash
./gradlew :FrenchHub:make makePluginsJson
```

Le workflow GitHub compile le seul fichier `FrenchHub.cs3`, vérifie syntaxiquement les providers JavaScript Nuvio et publie `plugins.json` dans la branche `builds`.

## Avertissement

La disponibilité d’un lecteur dépend du domaine distant, de Cloudflare et de l’état réel de l’hoster. Un provider peut rester activable dans l’extension tout en ne retournant temporairement aucun lecteur si son site est bloqué ou hors service.
