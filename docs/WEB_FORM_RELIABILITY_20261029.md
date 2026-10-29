# Diagnostic des parcours TikTok, Suno et YouTube

Les tests réels ont confirmé la génération Suno avec Paname offi et la programmation du Short YouTube. Le test TikTok a confirmé la réception du MP4 et le réglage de la date/heure, mais pas la programmation finale. Aucun clic final TikTok n’a été envoyé.

## Causes confirmées dans le code

1. `agent_keyboard.js` envoyait un `beforeinput` synthétique avant `execCommand`, puis pouvait envoyer `input/change` à nouveau. Les éditeurs à entités peuvent consommer le premier événement eux-mêmes. Le repli `Range.deleteContents/insertNode` contournait également leur état interne.
2. Le remplacement sélectionnait les seuls nœuds texte. Il pouvait laisser des entités non éditables. Les remplacements complets utilisent désormais `selectNodeContents` et les effacements une véritable commande `delete` du navigateur.
3. `readPage`, `getForm` et `agent_vision.js` parcouraient les contrôles de fond avant les dialogues ajoutés en fin de DOM. Les limites de 35/40/120 contrôles pouvaient masquer le formulaire actif. Les dialogues visibles sont maintenant prioritaires ; les plafonds restent bornés et les troncatures sont signalées.
4. L’API annonçait `keyboard`, mais son autorisation interne ne reconnaissait que la cible native. Le même gestionnaire traite désormais les cibles web et natives, sans retirer les protections de contexte et de domaine.
5. Les vérifications de navigation n’avançaient plus lorsque `evaluateJavascript` ne rendait pas son callback. Un watchdog termine maintenant la commande à son échéance et ignore les réponses tardives.
6. Le défilement acceptait seulement haut/bas. Les grandes pages TikTok décalées peuvent maintenant être déplacées horizontalement par une commande mesurée.

## Ce qui n’est pas prouvé

Le CPU/RAM du PC et le réseau n’ont pas été mesurés pendant les incidents. Les échecs de rendu ou de chargement n’établissent pas à eux seuls un manque de puissance. La réception d’un fichier dans le champ (`selected`) ne prouve toujours pas la réception serveur, et aucune publication n’est déclarée terminée sans confirmation de la plateforme.

## Vérifications

`bash validation/check.sh` compile et exécute les scripts Java/JavaScript, notamment les nouveaux cas : éditeur réactif à hashtags, refus sans DOM forcé, inspection d’un dialogue après 6000 contrôles et défilement horizontal dans les deux sens. Les tests Android ajoutent une WebView réelle : insertion unique/effacement d’entités, commande refusée, inspection de dialogue, routage direct clavier et délai de navigation sans callback. Le workflow Android existant compile `assembleDebug lintDebug`, exécute les tests sur émulateur et signe la Release sur main avec la clé persistante.
