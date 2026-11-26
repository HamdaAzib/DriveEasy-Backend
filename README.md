# DriveEasy – Backend (Spring Boot / Spring Cloud)

Plateforme de location de véhicules reposant sur une architecture **microservices**.
Projet individuel – Licence Informatique et Multimédia (LIM3) – SESAME – Module *Développement d'applications web et multimédia*.

> Interface de démonstration HTML/CSS/JavaScript : dossier séparé `frontend/`, à publier dans son propre dépôt. Angular 22 reste un bonus du cahier des charges et n'est pas inclus.

---

## 1. Description

DriveEasy remplace une application monolithique par un prototype microservices permettant de :

- gérer le parc automobile (statut, kilométrage, tarif journalier) ;
- gérer les clients et la validité de leur permis ;
- gérer le cycle de vie d'une location : réservation → départ → retour / annulation ;
- calculer automatiquement le prix (remises fidélité, pénalités de retard) ;
- fournir des statistiques (chiffre d'affaires, taux d'occupation, top véhicules).

> **Données : uniquement en mémoire (RAM).** Aucune base de données n'est utilisée (interdit par le cahier des charges). Les données de démonstration sont rechargées à chaque démarrage.

## 2. Architecture

```
                         ┌──────────────────────┐
   Client / Postman ───► │  API Gateway :8080   │───┐
   (Angular :4200)       └──────────┬───────────┘   │ trace
                                    │ lb://          ▼
         ┌──────────────────────────┼──────────────────────┐   ┌───────────────┐
         ▼                          ▼                      ▼   │ Zipkin :9411  │
 ┌────────────────┐       ┌────────────────┐     ┌────────────────────┐  └───────────────┘
 │ vehicule-service│◄──────│location-service│────►│   client-service   │
 │     :8081       │       │     :8083      │     │       :8082        │
 └────────────────┘       └────────────────┘     └────────────────────┘
        ▲ tous enregistrés dans ▼                 ▲ configuration depuis
 ┌────────────────────┐                   ┌────────────────────┐
 │ Eureka :8761       │                   │ Config Server :8888│
 └────────────────────┘                   └────────────────────┘
```

| Composant | Technologie | Port | Rôle |
|---|---|---|---|
| `service-registry` | Eureka Server | 8761 | Annuaire des services |
| `config-server` | Spring Cloud Config Server | 8888 | Configuration centralisée (native par défaut, Git optionnel) |
| `vehicule-service` | Spring Boot Web | 8081 | Parc automobile |
| `client-service` | Spring Boot Web | 8082 | Clients et permis |
| `location-service` | Spring Boot Web | 8083 | Cycle de vie des locations, orchestration |
| `api-gateway` | Spring Cloud Gateway | 8080 | Point d'entrée unique, routage, CORS, filtres |
| Zipkin | Docker `openzipkin/zipkin` | 9411 | Traçage distribué |

Communication inter-services (synchrone, via Eureka + load balancing) :

- `location-service` → `client-service` : éligibilité, mise à jour de la catégorie de fidélité ;
- `location-service` → `vehicule-service` : disponibilité, tarif, changement de statut ;
- `vehicule-service` → `location-service` : périodes occupées (pour `/vehicules/disponibles`) ;
- `client-service` → `location-service` : vérification d'une location active avant suppression.

## 3. Structure du dépôt

```
driveeasy-backend/
├── service-registry/
├── config-server/
├── config-repo/              # fichiers *.yml servis par le Config Server
│   ├── application.yml
│   ├── vehicule-service.yml
│   ├── client-service.yml
│   ├── location-service.yml
│   └── api-gateway.yml
├── vehicule-service/
├── client-service/
├── location-service/
├── api-gateway/
├── docker-compose.yml        # Zipkin
├── postman/                  # collection Postman
└── README.md
```

## 4. Prérequis

- JDK **21**
- Maven 3.9+
- Docker (pour Zipkin)
- Git
- (Bonus) Node.js compatible Angular 22

Versions utilisées : Spring Boot 3.4.x · Spring Cloud 2024.0.x.

## 5. Installation et lancement pas à pas

### 5.1 Cloner

```bash
git clone https://github.com/<votre-compte>/driveeasy-backend.git
cd driveeasy-backend
```

### 5.2 Lancer Zipkin

```bash
docker compose up -d
```
Interface : <http://localhost:9411>

### 5.3 Lancer les services — dans cet ordre

Un terminal par service (ou depuis VS Code) :

```bash
# 1. Service Registry
cd service-registry && ./mvnw spring-boot:run

# 2. Config Server
cd config-server && ./mvnw spring-boot:run

# 3. Microservices métier (dans n'importe quel ordre)
cd vehicule-service && ./mvnw spring-boot:run
cd client-service   && ./mvnw spring-boot:run
cd location-service && ./mvnw spring-boot:run

# 4. API Gateway
cd api-gateway && ./mvnw spring-boot:run
```

Sous Windows : `mvnw.cmd spring-boot:run`.

### 5.4 Vérifier

| Vérification | URL |
|---|---|
| Tableau de bord Eureka (5 clients enregistrés, service-registry exclu) | <http://localhost:8761> |
| Config Server | <http://localhost:8888/vehicule-service/default> |
| Santé d'un service | <http://localhost:8081/actuator/health> |
| Via la gateway | <http://localhost:8080/api/vehicules> |
| Traces | <http://localhost:9411> |

### 5.5 Lancer les tests

```bash
./mvnw test        # dans chaque module
```

## 6. Configuration centralisée

Chaque service ne contient qu'un `application.yml` minimal :

```yaml
spring:
  application:
    name: vehicule-service
  config:
    import: configserver:http://localhost:8888
```

Le Config Server sert les fichiers du dossier `config-repo/` (profil `native`, par défaut en local) ou d'un dépôt GitHub (profil `git`).
Propriétés externalisées : port, paramètres Eureka, paramètres Zipkin (`management.tracing.sampling.probability=1.0`), données de démonstration (« datasource » en mémoire) et propriétés métier (remises, pénalités, âge minimum…).

## 7. Routes de la gateway

| Chemin exposé | Service cible | Filtre |
|---|---|---|
| `/api/vehicules/**` | `lb://vehicule-service` | `StripPrefix=1` |
| `/api/clients/**` | `lb://client-service` | `StripPrefix=1` |
| `/api/locations/**` | `lb://location-service` | `StripPrefix=1` |

CORS autorisé pour `http://localhost:4200`. Un filtre global personnalisé journalise chaque requête et ajoute un en-tête `X-Request-Id`.

## 8. API REST

Toutes les URLs ci-dessous sont accessibles via la gateway avec le préfixe `/api` (ex. `GET http://localhost:8080/api/vehicules`).

### vehicule-service (8081)

| Méthode | URI | Description |
|---|---|---|
| GET | `/vehicules` | Liste, filtres : `categorie`, `carburant`, `statut`, `prixMax` |
| GET | `/vehicules/{id}` | Détail |
| POST | `/vehicules` | Ajout |
| PUT | `/vehicules/{id}` | Modification |
| DELETE | `/vehicules/{id}` | Suppression (refusée si loué) |
| PATCH | `/vehicules/{id}/statut` | Changement de statut |
| GET | `/vehicules/disponibles?dateDebut=&dateFin=` | Véhicules disponibles sur une période |

### client-service (8082)

| Méthode | URI | Description |
|---|---|---|
| GET | `/clients` | Recherche par nom / CIN / email |
| GET | `/clients/{id}` | Détail |
| POST | `/clients` | Création |
| PUT | `/clients/{id}` | Modification |
| DELETE | `/clients/{id}` | Suppression (refusée si location active) |
| GET | `/clients/{id}/eligibilite` | Âge ≥ 21 ans et permis valide |
| PATCH | `/clients/{id}/categorie` | Mise à jour de la catégorie |

### location-service (8083)

| Méthode | URI | Description |
|---|---|---|
| GET | `/locations` | Liste paginée, filtres : `statut`, `clientId`, `vehiculeId`, période |
| GET | `/locations/{id}` | Détail enrichi (client + véhicule) |
| POST | `/locations` | Création d'une réservation |
| POST | `/locations/simulation` | Simulation du prix sans création |
| PATCH | `/locations/{id}/depart` | Démarrage (EN_COURS) |
| PATCH | `/locations/{id}/retour` | Retour : `kmRetour`, calcul final, pénalités |
| PATCH | `/locations/{id}/annulation` | Annulation |
| GET | `/locations/statistiques` | CA, taux d'occupation, top véhicules |

Codes HTTP utilisés : 200, 201, 204, 400, 404, 409, 500. Les erreurs renvoient un corps JSON uniforme (`timestamp`, `status`, `error`, `message`, `path`).

## 9. Règles de gestion

- Immatriculation, CIN, email et numéro de permis **uniques** (409 sinon).
- Prix journalier > 0 ; année ≤ année courante.
- Un véhicule `LOUE` ou `MAINTENANCE` ne peut pas être réservé.
- Client éligible : âge ≥ 21 ans **et** permis non expiré.
- **Prix** = jours × prix journalier × coefficient catégorie − remise fidélité (FIDELE 5 %, VIP 10 %).
- **Pénalité de retard** = 1,5 × prix journalier × jours de retard.
- Départ : véhicule → `LOUE` ; retour : véhicule → `DISPONIBLE`.
- Catégorie client : `FIDELE` après 5 locations terminées, `VIP` après 15.
- Cohérence distribuée : si un appel inter-service échoue, la réservation est annulée / l'opération compensée.

## 10. Jeux de données de démonstration

Chargés au démarrage (en mémoire) :

- ~10 véhicules (statuts variés) ;
- ~8 clients (STANDARD / FIDELE / VIP, dont un permis expiré et un client mineur pour tester l'éligibilité) ;
- quelques locations (RESERVEE, EN_COURS, TERMINEE).

Pas d'authentification : aucun compte de test n'est nécessaire.

## 11. Scénario de test rapide (Postman)

1. `GET /api/clients/1/eligibilite`
2. `POST /api/locations/simulation`
3. `POST /api/locations` → 201 (RESERVEE)
4. `PATCH /api/locations/{id}/depart` → véhicule `LOUE`
5. `PATCH /api/locations/{id}/retour` → montant final + pénalité éventuelle
6. `GET /api/locations/statistiques`
7. Ouvrir Zipkin et repérer la trace `api-gateway → location-service → client-service / vehicule-service`

Importer `postman/DriveEasy.postman_collection.json` dans Postman, puis lancer les requêtes dans l'ordre. Les dates sont générées automatiquement et l'identifiant de location est repris après la création.

## 12. Captures d'écran

À joindre au rapport :

1. Le tableau de bord Eureka avec la Gateway, Config Server et les trois services métier enregistrés (le registre Eureka lui-même reste le serveur de découverte).
2. Config Server avec une configuration chargée depuis `config-repo/`.
3. Postman avec la simulation, la réservation, le départ et le retour.
4. Zipkin avec une trace qui contient `api-gateway`, `location-service`, `client-service` et `vehicule-service`.

## 13. Dépannage

| Problème | Solution |
|---|---|
| Un service ne démarre pas (config introuvable) | Vérifier que le Config Server tourne avant lui |
| Service absent d'Eureka | Attendre ~30 s ; vérifier le port 8761 |
| `503` via la gateway | Service pas encore enregistré dans Eureka |
| Pas de traces dans Zipkin | Vérifier Docker et `sampling.probability=1.0` |
| Port déjà utilisé | Arrêter le processus ou modifier le port dans `config-repo/` |

## 14. Auteur

Projet réalisé par **<Nom Prénom>** – LIM3 – SESAME – Année universitaire 2026/2027.
Enseignant : Karim Ben Romdhane.


## Lancer le site pour la validation (Windows)

Depuis la racine du projet, démarrer les six services avec Java 21 :

    .\run-driveeasy.ps1

Dans un second terminal, lancer l'interface séparée :

    cd frontend
    python -m http.server 4200

Ouvrir ensuite http://localhost:4200. La Gateway expose l'API sur http://localhost:8080. Après une modification du backend, arrêter d'abord les services s'ils tournent, puis les reconstruire et les relancer avec :

    .\run-driveeasy.ps1 -Build

Le frontend appelle les API de la Gateway à l'adresse `http://localhost:8080` ; CORS autorise `http://localhost:4200`. Le dossier `frontend/` est exclu du dépôt backend par `.gitignore` afin de pouvoir le publier séparément. Le frontend Angular 22 mentionné dans le cahier des charges reste un bonus.

Le stockage est exclusivement en mémoire, conformément au cahier des charges : les données sont réinitialisées au redémarrage du service concerné. Aucun moteur de base de données ne doit être configuré.

En local, Config Server utilise le profil native et les fichiers config-repo/. Pour utiliser un dépôt Git de configuration, renseigner DRIVEEASY_CONFIG_GIT_URI puis activer le profil git avant de démarrer Config Server.

Zipkin se lance avec docker compose up -d lorsque le moteur Docker est disponible. Son interface est http://localhost:9411.
