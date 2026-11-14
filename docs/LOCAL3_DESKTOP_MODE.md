# CHK Browser Local 3 — mode PC réel

14 novembre 2026.

L'ancienne bascule de compatibilité ne modifiait que le User-Agent. La WebView est désormais mesurée à une largeur cible de 1366 ou 1920 dp avant d'être réduite visuellement pour l'écran Android. La largeur CSS de la page est ensuite relue pour vérifier l'effet réel.

## Utilisation
- Toucher PC pour Mobile / Ordinateur ; appui long pour choisir 1366, 1920 ou Automatique.
- Transport MCP existant : action desktop, tool browser, operation get_viewport, set_viewport ou full_page_scan.
- Exemple : {"tool":"browser","operation":"set_viewport","expected_host":"play.google.com","mode":"desktop","width":1366}
- Exemple de lecture : {"tool":"browser","operation":"get_viewport","expected_host":"play.google.com"}
- Exemple de scan : {"tool":"browser","operation":"full_page_scan","expected_host":"play.google.com"}
- Exemple de diagnostic : {"tool":"browser","operation":"get_page_status","expected_host":"play.google.com"}
- Auto : bureau pour play.google.com et github.com ; mobile ailleurs.
- Par défaut, pas de rechargement : protège les saisies non sauvegardées. Option explicite reload:true à vérifier ultérieurement.

## Limites
Le scan de l'ensemble du DOM est borné à 260 contrôles et n'est PAS une capture visuelle multi-défilement. La largeur CSS mesurée ne prouve pas le succès des clics ou de la publication. La reprise de transferts binaires, la capture par tuiles et le zoom/pan piloté restent au programme. Les modes n'affectent pas les paramètres de Tor, les données utilisateur ni le mécanisme de signature.
