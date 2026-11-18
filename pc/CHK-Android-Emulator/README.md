# Navigateur Android dans CHK

Le compagnon affiche et pilote un AVD Android Studio sur le PC. Lancer l’AVD à côté de CHK Universal Control 1.0.4. Le compagnon parle à ADB sur `127.0.0.1:5037` et écoute en HTTPS sur un port local distinct. Fichiers, Studio, Cut et Notes continuent d’être gérés par CHK sur le téléphone.

## Démarrer sur Windows

1. Dans Android Studio, installer **Android Emulator** et **Android SDK Platform-Tools**. Créer un appareil virtuel dans Device Manager. Choisir une image **Google Play** si l’AVD doit installer des applications depuis le Play Store. Démarrer l’AVD et attendre son écran d’accueil.
2. Installer Python 3.10 ou plus récent et vérifier qu’OpenSSL fonctionne. Si `openssl` ou `adb` est absent du PATH, passer son chemin complet avec `--openssl` ou `--adb`.
3. Dans PowerShell, depuis la racine du dépôt, vérifier le numéro de l’AVD et l’adresse IPv4 locale du PC.

   ```powershell
   adb devices
   ipconfig
   ```

4. Lancer le compagnon avec l’IPv4 du PC et le numéro d’AVD affiché par ADB. Le téléphone et le PC doivent pouvoir se joindre par le réseau local. Autoriser le port TCP choisi sur le réseau privé du PC.

   ```powershell
   py pc/CHK-Android-Emulator/chk_emulator.py --host 192.168.1.20 --serial emulator-5554
   ```

5. Dans CHK, ouvrir **Navigateur Android → Associer le compagnon PC** et coller la ligne d’appairage affichée par la console. Le code contient l’empreinte du certificat et le jeton de la session. Un redémarrage du compagnon renouvelle le code. Conserver la console ouverte pendant l’utilisation.
6. Ouvrir le Play Store de l’AVD pour installer les applications compatibles. La vidéo exportée par Studio ou Cut peut être sélectionnée avec **Envoyer un média**. Retrouver le fichier dans **Téléchargements → CHK** dans l’AVD. Le transfert est contrôlé par SHA-256 et limité à 2 Gio. Dans une application sociale, sélectionner le fichier via son importateur Android, selon les formats et sources qu’elle accepte.

### Écran et commandes Local 3

L’écran se rafraîchit environ une fois par seconde selon le débit et la vitesse de l’AVD. Il accepte les pressions et les glissements. Les commandes Retour, Accueil et Applications récentes s’appliquent à l’AVD. La boîte de saisie ADB accepte les caractères ASCII. Le clavier affiché par Android prend en charge les accents et les emojis. La connexion et la disponibilité des fonctions de programmation dépendent de l’application sociale installée et du compte.

Avec le connecteur Local 3 actif, ouvrir **Navigateur Android → ⋮ → Autoriser Local 3 pendant 20 min**. Activer le partage des aperçus dans Réglages CHK pour recevoir les images. `task_submit` utilise `action: "desktop"` et les `args` ci-dessous, puis `task_get` lit la réponse.

| `operation` | Arguments supplémentaires | Résultat |
| --- | --- | --- |
| `status` | | Appairage accessible et état ADB lorsque l’accès Local 3 est autorisé |
| `observe` | | Capture JPEG, coordonnées écran d’origine et `frame_id` |
| `tap` | `frame_id`, `x`, `y` | Pression envoyée |
| `swipe` | `frame_id`, `x`, `y`, `end_x`, `end_y` | Glissement envoyé |
| `key` | `key` (`back`, `home`, `recent`, `enter`, `delete`) | Touche envoyée |
| `text` | `text` | Texte ASCII envoyé |
| `send_media` | `id`, `workspace_path` | Transfert demandé |
| `media_status` | `id` | Statut, chemin et empreinte si le transfert est terminé |
| `stop` | | Autorisation Local 3 terminée |

Tous les `args` contiennent aussi `"tool": "android_emulator"`. Après une interaction, observer la réponse d’Android avant de poursuivre. Sur un réseau social, considérer une vidéo comme programmée après la vérification de sa présence dans la liste des publications programmées et de la date affichée.
