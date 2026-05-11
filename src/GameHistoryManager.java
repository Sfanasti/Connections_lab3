import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 Gestisce la persistenza dei risultati delle partite terminate su file JSON (gameHistory.json).

 Per ogni partita viene salvata la lista dei risultati individuali di ogni giocatore
 (categorie trovate, errori, punteggio, esito). Viene anche salvato l'ID dell'ultima
 partita completamente terminata, in modo che al riavvio del server si possa riprendere
 dalla partita successiva.

 Tutti i metodi pubblici sono sincronizzati perché vengono chiamati sia dal thread del timer
 sia dai thread del pool che gestiscono le richieste dei client.
 */
public class GameHistoryManager {

    /* Risultato individuale di un giocatore per una singola partita. */
    public static class PlayerResult {
        public String username;
        public int correctGroups; // categorie indovinate
        public int mistakes;
        public int score;
        public boolean won;
        public boolean timedOut;  // true se la partita è scaduta
    }

    /*
     Wrapper usato solo per la serializzazione JSON: raggruppa lastCompletedGameId
     e la mappa dei risultati in un unico oggetto da scrivere su file.
     */
    private static class HistoryData {
        public int lastCompletedGameId = -1;
        public Map<Integer, List<PlayerResult>> results = new ConcurrentHashMap<>();
    }

    private final Path path = Paths.get("gameHistory.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // gameId → lista di risultati dei giocatori per quella partita
    private Map<Integer, List<PlayerResult>> history = new ConcurrentHashMap<>();

    /*
     ID dell'ultima partita il cui ciclo è completamente terminato (timer scaduto +
     tutti i timeout registrati + avanzamento alla partita successiva).
     Non viene aggiornato quando un singolo giocatore vince/perde a metà partita.
     -1 = il server non ha ancora completato nemmeno una partita (primo avvio assoluto).
     */
    private volatile int lastCompletedGameId = -1;

    public GameHistoryManager() {
        load(); // carica lo storico dal file se esiste
    }

    // ===================== SCRITTURA =====================

    /*
     Registra il risultato individuale di un giocatore per una partita.
     Chiamato sia a fine partita anticipata (vittoria/sconfitta) sia al timeout.
     */
    public synchronized void recordResult(int gameId, String username,
                                          int correctGroups, int mistakes,
                                          int score, boolean won, boolean timedOut) {
        history.computeIfAbsent(gameId, k -> new ArrayList<>())
                .add(buildResult(username, correctGroups, mistakes, score, won, timedOut));
        save();
    }

    /*
     Segna la partita come completamente terminata e aggiorna lastCompletedGameId.
     Chiamato dal timer UNA SOLA VOLTA per ciclo, dopo aver gestito tutti i timeout
     e prima di avanzare alla partita successiva (gameManager.next()).
     */
    public synchronized void markGameCompleted(int gameId) {
        if (gameId > lastCompletedGameId) {
            lastCompletedGameId = gameId;
            save();
        }
    }

    // ===================== LETTURA =====================

    /* Restituisce tutti i risultati registrati per una partita (lista vuota se nessuno ha giocato). */
    public synchronized List<PlayerResult> getResults(int gameId) {
        return history.getOrDefault(gameId, Collections.emptyList());
    }

    /* Cerca il risultato di uno specifico giocatore in una specifica partita. Null se non trovato. */
    public synchronized PlayerResult getPlayerResult(int gameId, String username) {
        return getResults(gameId).stream()
                .filter(r -> r.username.equals(username))
                .findFirst().orElse(null);
    }

    /*
     Restituisce l'ID dell'ultima partita completata.
     Usato da GameManager al riavvio per determinare da quale partita ripartire:
     se -1, il server riparte dalla partita 0 (primo avvio assoluto).
     */
    public synchronized int getLastGameId() {
        return lastCompletedGameId;
    }

    // ===================== SUPPORTO =====================

    /* Costruisce un oggetto PlayerResult dai parametri forniti. */
    private PlayerResult buildResult(String username, int cg, int m, int s, boolean w, boolean t) {
        PlayerResult r = new PlayerResult();
        r.username = username; r.correctGroups = cg; r.mistakes = m;
        r.score = s; r.won = w; r.timedOut = t;
        return r;
    }

    // ===================== PERSISTENZA =====================

    private void load() {
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            HistoryData data = gson.fromJson(json, HistoryData.class);
            if (data != null) {
                this.lastCompletedGameId = data.lastCompletedGameId;
                if (data.results != null) this.history = data.results;
            }
        } catch (IOException e) {
            System.err.println("[HISTORY] Impossibile caricare: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            HistoryData data = new HistoryData();
            data.lastCompletedGameId = this.lastCompletedGameId;
            data.results = this.history;
            Files.write(path, gson.toJson(data).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[HISTORY] Impossibile salvare: " + e.getMessage());
        }
    }
}
