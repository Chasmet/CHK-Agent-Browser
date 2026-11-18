# Android invité dans CHK Agent Browser

L’émulateur s’ouvre dans CHK Browser et utilise le moteur ARM64 embarqué. La VM n’utilise ni ADB sur le téléphone hôte ni les applications installées sur celui-ci. L’écran Android invité est capturé via le moniteur QMP de QEMU. CHK envoie à ce moniteur les gestes et le clavier. Le canal QMP utilise un socket Unix privé du dossier de la VM.

## Test sur téléphone

1. Mettre à jour CHK Browser depuis **Réglages → Mise à jour automatique**. Pour les essais sur une branche, utiliser l’APK debug du workflow de prévisualisation.
2. Ouvrir **Navigateur Android → Installer Android → Télécharger et installer**. Prévoir au moins 6 Gio libres au départ et de la marge pour les données de la machine virtuelle. CHK vérifie le SHA-256 de l’archive avant d’en extraire les disques.
3. Démarrer Android. Le premier démarrage sous émulation logicielle peut durer plusieurs minutes. Le menu contient le journal du moteur. Vérifier qu’Android arrive à l’écran de configuration ou d’accueil et qu’un geste CHK provoque un changement visible.
4. Tester une application installée dans la VM. Le Play Store et les comptes du téléphone hôte sont séparés. La compatibilité avec les services de Google et avec YouTube, Instagram ou TikTok est à vérifier sur cette image avant de connecter des comptes de production.
5. Pour tester l’agent, connecter Local 3, activer le partage des aperçus puis **Autoriser Local 3 pendant 20 min** dans le menu de l’espace Android. Utiliser `desktop` avec `tool=android_emulator` et les opérations `status`, `observe`, `tap`, `swipe`, `key`, `text`, `send_media`, `media_status` ou `stop`. Les coordonnées des actions sont en pixels de l’image originale de l’invité et suivent un `frame_id` récent. Un geste envoyé doit ensuite être vérifié dans une nouvelle capture.
6. **Envoyer un média** dépose une copie dans le dossier d’échange. Le prototype échange un fichier à la fois, jusqu’à 480 Mio. Redémarrer l’invité pour monter le support USB virtuel en lecture seule puis vérifier que l’invité reconnaît le fichier. `media_status` indique `staged` après copie sur le téléphone.

## Sources

- [Moteur Android ARM64 et adaptations Bionic de Podroid](https://github.com/ExTV/Podroid/tree/e3fc1befa3c135d28bf8d40e3c2264d93fea87ef). `build-engine.sh` récupère le Dockerfile à ce commit et ajoute un étage d’export des fichiers QEMU, du lanceur, de libslirp et du micrologiciel. Les sources et les correctifs natifs suivis sont dans ce Dockerfile et le dépôt associé.
- [Projet QEMU](https://www.qemu.org/) et [archives des sources](https://download.qemu.org/). La version est fixée dans le Dockerfile Podroid.
- [Image LineageOS ARM64 et code de construction](https://github.com/jqssun/android-lineage-qemu/releases/tag/v2026.10.18). L’image reste un téléchargement séparé.
