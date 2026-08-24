# JARVIS (Veridian)

Assistant vocal Android — projet remis à zéro le 24/08/2026 à la demande de l'utilisateur.

## État actuel

Squelette minimal : une seule activité (`MainActivity`), aucune fonctionnalité métier.
L'application compile et se lance (écran vide avec le nom de l'app).

Tout le code précédent (appels, SMS, contacts, agenda, Obsidian/vault, IA multi-fournisseurs,
box internet, IA locale embarquée, wake-word, génération de site web, etc.) a été retiré du
dépôt. Il reste consultable dans l'historique git (`git log`) sur les commits antérieurs à la
remise à zéro, si besoin de retrouver un bout de logique en reconstruisant une fonctionnalité.

## Prochaines étapes

Les fonctionnalités seront reconstruites une par une, dans l'ordre choisi par l'utilisateur.

## Clés API

Aucune clé n'est jamais codée en dur dans le dépôt (public). Toute clé nécessaire à une
future fonctionnalité doit être saisie par l'utilisateur dans l'application elle-même.
