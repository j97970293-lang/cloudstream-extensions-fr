# Audit de compatibilité — providers français CloudStream

## Pistes à examiner

| Projet | URL | Motif de vérification |
|---|---|---|
| CuxPlug | https://github.com/ycngmn/CuxPlug | Dépôt international signalé comme contenant des extensions françaises. |
| CloudStream Multilingual | https://codeberg.org/cloudstream/cloudstream-extensions-multilingual | Dépôt historique mentionnant notamment FrenchStreamProvider et MesFilms. |
| CNCVerse | https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension | Source déjà étudiée partiellement ; peut contenir des logiques réutilisables pour MovieBox. |
| CloudStream Wiki | https://cloudstream.miraheze.org/wiki/List_of_extensions | Répertoire de dépôts à recouper avec le code source et des tests réseau. |

## Critère d’intégration

Un provider ne sera retenu que si son code est lisible, que son endpoint de recherche et au moins une résolution de lecteur répondent depuis un test reproductible, et que son ajout n’introduit pas de doublons avec les quatre providers stables de FrenchHub.

## Constats de recherche

Le dépôt CuxPlug présente trois modules français : **FrenchStream**, **AnimeSama** et **AniZone**. Son dernier commit affiché est daté du 30 mars 2026 et mentionne une correction AnimeSama ; il est donc prioritaire pour un audit de code et de réseau. URL : https://github.com/ycngmn/CuxPlug

Le dépôt CloudStream Multilingual est un catalogue historique majoritairement non francophone. Il référence des sources françaises anciennes, mais son activité de branche principale est beaucoup plus ancienne et ne constitue pas une preuve de fonctionnement actuel. Il sera seulement utilisé comme référence de structure, pas comme source à intégrer sans validation. URL : https://codeberg.org/cloudstream/cloudstream-extensions-multilingual

## Analyse technique CuxPlug

Le provider `AnimeSama` de CuxPlug utilise `https://anime-sama.to`, une recherche POST sur `/template-php/defaut/fetch.php`, puis récupère le script `episodes.js` depuis les pages de saison. Il s’agit d’un chemin entièrement Kotlin/CloudStream, sans runtime JS externe. Il mérite donc un test réseau de recherche puis de récupération des liens d’épisode avant toute reprise.

Le provider `FrenchStream` CuxPlug emploie `engine/ajax/film_api.php?id=<id>` pour les films. Ce mécanisme est déjà très proche du provider French-Stream existant de FrenchHub : il n’est pas considéré comme une nouvelle source à ajouter, mais ses détails serviront à recouper le correctif cinéma existant.

Le dépôt FrenchRepo de mouradchaouche a été modifié le 14 juillet 2026 et présente notamment **1Jour 1Film**, **Wiflix**, **Frembed**, **FsMirrorLol** et **FrenchAnime**. Son README déclare 1Jour 1Film comme fonctionnel. URL : https://github.com/mouradchaouche/cloudstream-frenchrepo

Le provider `JourFilm` est un module Kotlin CloudStream direct : recherche WordPress `?s=`, fiches, séries et endpoint AJAX `wp-admin/admin-ajax.php` avec l’action `doo_player_ajax`. Il ne dépend pas d’un runtime externe, mais nécessite `CloudflareKiller`. Sa disponibilité et au moins une réponse AJAX devront être contrôlées avant toute intégration.

## Validation réseau — 18 août 2026

| Candidat | Résultat vérifiable | Décision |
|---|---|---|
| AnimeSama (CuxPlug) | L’accueil et l’endpoint de recherche retournent `403` Cloudflare depuis un client Android standard. | Ne pas porter. |
| 1Jour 1Film | L’ancien domaine redirige vers `1jour1film2026.site/go`, qui retourne `403` Cloudflare pour l’accueil et la recherche. | Ne pas porter. |
| Frembed | La configuration dynamique pointe vers `https://frembed.casa` et sa fiche de film répond `200`. L’endpoint de flux redirige toutefois vers un embed Playmogo protégé par Cloudflare (`403`). | Ne pas porter tant qu’un lien final reproductible n’est pas accessible. |

Ces résultats excluent les trois candidats de l’intégration automatique : ils ne satisfont pas la condition de résolution de lecteur vérifiable au moment de l’audit.
