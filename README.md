# 🔗 Connections — Progetto di Laboratorio di Reti
**Corso A · Anno Accademico 2025/26**  
Corsi Leonardo · Matricola `656276`

---

## 📖 Introduzione

Il progetto si basa sul gioco **"Connections"** presente sul sito del New York Times.

L'obiettivo è indovinare set di 4 parole accomunate da una categoria comune, che può variare da forme grammaticali a temi veri e propri.

Gli utenti possono connettersi al server e, dopo la registrazione/login, accedere direttamente alla partita in corso — permettendo la partecipazione insieme agli altri giocatori attivi.

---

## 🏗️ Architettura

Il progetto implementa l'architettura **Client-Server** utilizzando:

| Protocollo | Utilizzo |
|---|---|
| **TCP** | Connessione principale Client ↔ Server |
| **UDP Multicast** | Gestione delle notifiche asincrone |

---

## 🎯 Scelte Progettuali

### NIO + ThreadPool (Lato Server)
Il server usa **Java NIO** con un `Selector` per gestire tutte le connessioni TCP su un unico thread dedicato. Il Selector monitora tutti i canali registrati e segnala quando uno è pronto per la lettura. Il messaggio viene quindi delegato a una **FixedThreadPool**, che lo processa in parallelo, mantenendo il Selector libero per continuare a gestire gli altri canali.

### Configurazione tramite `.properties`
Sia il server che il client leggono i parametri da file `.properties` invece di accettarli come argomenti a riga di comando. Questo rende la modifica dei parametri strutturali più comoda, eliminando la necessità di ricordare ogni volta la sintassi e i valori esatti del comando.

### Persistenza JSON con Gson
I dati dei giocatori, la storia delle partite e il database delle parole sono salvati su file JSON, gestiti tramite la libreria **Gson**. Le operazioni di salvataggio sono sincronizzate per evitare scritture concorrenti corrotte.

### Notifiche via UDP Multicast
Le notifiche ai client (nuova partita iniziata, classifica finale, server in shutdown) vengono inviate via **UDP Multicast**. Il multicast permette di raggiungere tutti i client con una sola trasmissione, evitando l'iterazione su tutte le connessioni TCP.

### Sessione per Connessione
Ogni connessione TCP ha un oggetto `Session` dedicato che mantiene lo stato del giocatore durante la partita (username, categorie indovinate, numero di errori, flag di fine partita). Questo consente anche il recupero dello stato dopo un logout/login nella stessa partita.

---

## 🧵 Schema dei Thread

### Lato Server

| Thread | Responsabilità |
|---|---|
| **Main Thread** | Inizializza le strutture dati, carica i file `.json` e avvia il loop del Selector NIO |
| **FixedThreadPool(20)** | Pool da 20 thread che processa le richieste dei client in parallelo |
| **GameTimer Thread** | Gestisce il ciclo delle partite (avvio, pausa, transizione), resetta le sessioni e invia la classifica via multicast |
| **ShutDown Hook** | Rimane in attesa di `CTRL+C`, poi avvia lo shutdown: ferma pool e timer, chiude i canali e notifica i client |

### Lato Client

| Thread | Responsabilità |
|---|---|
| **Main Thread** | Loop interattivo: legge l'input utente, invia richieste TCP e mostra le risposte |
| **Multicast Listener Thread** | Thread demone che ascolta le notifiche UDP Multicast dal server e le stampa a schermo |

---

## 🗂️ Strutture Dati

| Classe | Struttura | Utilizzo |
|---|---|---|
| **PlayerManager** | `ConcurrentHashMap<String, Player>` | Mappa dei giocatori con accesso concorrente |
| **PlayerManager** | `Set<String>` | Giocatori online, supportato da `ConcurrentHashMap` |
| **GameManager** | `ConcurrentHashMap<Integer, Game>` | Caricamento partite disponibili all'avvio |
| **GameManager** | `ConcurrentHashMap<Integer, GameStats>` | Aggiornamenti concorrenti delle statistiche |
| **ServerMain** | `Set<Session>` | Iterazione sicura durante il reset delle partite |
| **GameHistoryManager** | `ConcurrentHashMap<Integer, List<PlayerResult>>` | Aggiornamenti concorrenti dello storico |
| **Session** | `HashSet<String>` | Categorie indovinate (accesso protetto da `synchronized`) |
| **Session** | `ByteBuffer` | Buffer NIO dedicato per ogni connessione TCP |
| **Player** | `HashSet<String>` | Recupero categorie attive e stato dopo il login |
| **Player** | `int[]` (6 elementi) | Istogramma errori per partita (0–5 errori) |

### File di Salvataggio JSON

| File | Contenuto |
|---|---|
| `Connections_Data.json` | Giochi caricati a runtime |
| `gameHistory.json` | Storico delle partite |
| `players.json` | Credenziali e statistiche personali degli utenti |

---

## 🔒 Primitive di Sincronizzazione

### ConcurrentHashMap
Usata per tutte le collezioni principali. Garantisce letture non bloccanti e riduce la contesa tra thread.

### Metodi `synchronized`
I metodi di `PlayerManager`, `GameManager` e `GameHistoryManager` che modificano lo stato condiviso e scrivono su file sono dichiarati `synchronized`, garantendo che le operazioni di lettura/modifica/salvataggio non si sovrappongano tra thread diversi.

### Synchronized su `Session`
La gestione della proposta (`handleSubmitProposal`) è sincronizzata sull'oggetto `Session` del giocatore specifico, evitando race condition tra il thread che processa la proposta e il timer thread che resetta le sessioni a fine partita.

### `volatile`
Le variabili condivise tra thread che richiedono solo **visibilità** (non atomicità) sono dichiarate `volatile`:

| Variabile | Descrizione |
|---|---|
| `running` | Flag di shutdown, letto dal loop del Selector |
| `currentGame` | Riferimento alla partita corrente, aggiornato dal timer |
| `pause` | Flag di pausa tra una partita e l'altra |
| `startTime` | Timestamp di inizio partita, usato per il timeout |
| `lastCompletedGame` | ID dell'ultima partita completata, per il recovery al login |

---

## ⚙️ Compilazione ed Esecuzione

### Prerequisiti
- **Java 8+** (testato con OpenJDK 1.8.0_482)
- Librerie nella cartella `lib/` (già incluse nel repository):
  - `gson-2.13.1.jar` → serializzazione/deserializzazione JSON

### Compilazione
Dalla directory radice del progetto:

```bash
# Linux / macOS
javac -cp "lib/gson-2.13.1.jar:." src/*.java -d bin/

# Windows (sostituire ":" con ";")
javac -cp "lib/gson-2.13.1.jar;." src/*.java -d bin/
```

### Creazione dei JAR
```bash
jar cvfm Server.jar server-manifest.txt -C bin/ .
jar cvfm Client.jar client-manifest.txt -C bin/ .
```

### Esecuzione
```bash
# Avviare prima il server, poi il client in terminali separati!
java -jar Server.jar
java -jar Client.jar
```

> **Nota:** Non sono necessari parametri a riga di comando. Tutti i dati di configurazione sono presenti nei file `.properties` forniti.

---

## 🕹️ Sintassi delle Operazioni

### Menu non autenticato

| Comando | Descrizione |
|---|---|
| `register` | Registrazione di un nuovo utente |
| `login` | Autenticazione di un utente |
| `updateCredentials` | Aggiornamento delle credenziali |
| `exit` | Chiude il terminale (oppure `CTRL+C`) |

### Menu autenticato

| Comando | Descrizione |
|---|---|
| `play` | Invia una proposta di 4 parole |
| `stats` | Mostra le statistiche del giocatore |
| `leaderboard` | Classifica top-k utenti o rank di un giocatore specifico |
| `gameinfo` | Info sulla partita in corso o su una partita conclusa |
| `gamestats` | Statistiche aggregate sulla partita |
| `logout` | Disconnessione del profilo dal server |
| `exit` | Chiude il terminale (oppure `CTRL+C`) |
