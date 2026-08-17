# Observations externes — French-Stream

Le 17 août 2026, la page `https://french-stream.one/films` a exposé une fiche de test : **Toy Story 5** à l’URL `https://french-stream.one/index.php?newsid=15128330`.

La requête GET vers `https://french-stream.one/engine/ajax/film_api.php?id=15128330`, avec un User-Agent navigateur et le referer de la fiche, a renvoyé un JSON contenant `players`. Les lecteurs observés comportaient notamment `fsvid.lol`, `vidzy.cc`, `kokoflix.lol` et `vidaraa.cc`, avec des déclinaisons `default`, `vostfr` et `vfq`.

Les endpoints hypothétiques `get_movie_players.php` et `get_player.php` ont répondu 404 pour le même identifiant. Le correctif doit donc conserver `film_api.php` comme voie primaire et ajouter un fallback de recherche/matching pour les cas où cette API ne renvoie pas de lecteurs.
