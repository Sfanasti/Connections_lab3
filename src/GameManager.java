import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 Gestisce il ciclo di vita delle partite di Connections.

 Carica tutte le partite dal file JSON all'avvio (in streaming per non caricare
 tutto in memoria), tiene traccia della partita corrente e del tempo rimanente,
 calcola i punteggi e gestisce le statistiche aggregate per partita (partecipanti,
 vincitori, ecc.).

 I metodi che modificano lo stato condiviso (currentGame, pause, gameStatsMap)
 sono sincronizzati perché chiamati sia dal timer sia dai thread del pool.
 */
public class GameManager {

    /* Statistiche aggregate in memoria per una singola partita in corso. */
    public static class GameStats {
        public int participants = 0; // giocatori che hanno inviato almeno una proposta valida
        public int finished = 0;     // giocatori che hanno vinto o perso (non timeout)
        public int winners = 0;      // giocatori che hanno vinto
    }

    // Tutte le partite caricate dal JSON, indicizzate per gameId
    private final Map<Integer, Game> games = new ConcurrentHashMap<>();

    /*
     Statistiche in memoria per le partite correnti/passate.
     Nota: non persistenti → si azzerano al riavvio del server.
     Per le partite passate si usa GameHistoryManager.
    */
    private final Map<Integer, GameStats> gameStatsMap = new ConcurrentHashMap<>();

    private volatile Game currentGame; // partita attualmente in corso
    private final int duration;        // durata di ogni partita in secondi (da config)
    private volatile long startTime;   // timestamp di inizio della partita corrente (ms)
    private volatile boolean pause = false; // true durante la pausa tra una partita e l'altra

    // ===================== COSTRUTTORE =====================

    /*
     Carica il database delle partite e determina da quale partita ripartire.
     Se il server è già stato avviato in precedenza, riparte dalla partita successiva
     all'ultima completata (letta da GameHistoryManager).
     */
    public GameManager(String fileName, int duration, GameHistoryManager history) {
        this.duration = duration;
        loadGamesStreaming(fileName);
        if (!games.isEmpty()) {
            int lastId = history.getLastGameId(); // -1 se primo avvio assoluto
            // (lastId + 1) % games.size() fa il wrap-around a 0 quando si supera l'ultima partita
            int startId = (lastId + 1) % games.size();
            this.currentGame = games.getOrDefault(startId, games.get(0));
            this.startTime = System.currentTimeMillis();
        } else throw new RuntimeException("Nessuna partita caricata.");
    }

    // ===================== GESTIONE PARTITE =====================

    /* Avvia la partita corrente (registra il timestamp di inizio). */
    public synchronized void startGame() {
        this.startTime = System.currentTimeMillis();
        System.out.println("[GAME] Partita globale #" + currentGame.id + " iniziata.");
    }

    /* Mette il server in pausa (chiamato dal timer quando il tempo scade). */
    public synchronized void startTransition() {
        this.pause = true;
        System.out.println("[GAME] Tempo scaduto! La prossima partita inizierà a breve");
    }

    /* Avanza alla partita successiva (wrap-around dopo l'ultima). */
    public synchronized void next() {
        int nextId = (currentGame.id + 1) % games.size();
        this.currentGame = games.get(nextId);
        this.startTime = System.currentTimeMillis();
        this.pause = false;
        System.out.println("[GAME] Nuova partita iniziata! codice partita: " + currentGame.id);
    }

    public Game getCurrentGame()   { return currentGame; }
    public boolean isPaused()      { return pause; }

    /* Restituisce i secondi rimanenti alla fine della partita (min 0). */
    public long getTimeRemaining() {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        return Math.max(0, duration - elapsed);
    }

    public Game getGameById(int gameId) {
        return games.get(gameId);
    }

    // ===================== PUNTEGGIO =====================

    /*
     Calcola il punteggio per una partita secondo le regole del progetto:
     +6/+12/+18 per 1/2/3+ gruppi corretti, -4 per ogni errore.
     */
    public int score(int correct_groups, int mistakes) {
        int score = 0;
        if (correct_groups == 1) score += 6;
        else if (correct_groups == 2) score += 12;
        else if (correct_groups >= 3) score += 18;
        score -= mistakes * 4;
        return score;
    }

    // ===================== STATISTICHE PER PARTITA =====================

    /* Restituisce (o crea) le statistiche in memoria per una partita. */
    public synchronized GameStats getOrCreateStats(int gameId) {
        return gameStatsMap.computeIfAbsent(gameId, k -> new GameStats());
    }

    /*
     Registra la partecipazione di un giocatore (prima proposta valida inviata).
     Va chiamato solo alla prima submitProposal valida per evitare doppio conteggio.
     */
    public synchronized void recordParticipation(int gameId) {
        getOrCreateStats(gameId).participants++;
    }

    /*
     Registra l'esito finale di un giocatore (vittoria o sconfitta, non timeout).
     I timeout vengono gestiti direttamente dal timer in ServerMain.
     */
    public synchronized void recordResult(int gameId, boolean won, int score) {
        GameStats stats = getOrCreateStats(gameId);
        stats.finished++;
        if (won) stats.winners++;
    }

    // ===================== VERIFICA PROPOSTA =====================

    /*
     Controlla se le 4 parole dell'utente corrispondono a una delle categorie della partita corrente.
     Il confronto è case-insensitive (le parole vengono normalizzate in uppercase).

     @return la categoria trovata, oppure null se la proposta è sbagliata
     */
    public synchronized Category getMatchingCategory(Set<String> userWords) {
        if (userWords == null || userWords.size() != 4) return null;
        for (Category cat : currentGame.categories) {
            Set<String> catWords = cat.words.stream()
                    .map(w -> w.trim().toUpperCase())
                    .collect(java.util.stream.Collectors.toSet());
            if (catWords.equals(userWords)) return cat;
        }
        return null;
    }

    // ===================== CARICAMENTO DATI =====================

    /*
     Carica le partite dal file JSON in modo streaming (JsonReader di Gson).
     Il file può contenere centinaia di partite: la lettura streaming evita di
     caricarle tutte in memoria contemporaneamente prima di fare il parsing.
     */
    private void loadGamesStreaming(String fileName) {
        Gson gson = new Gson();
        try (JsonReader reader = new JsonReader(new FileReader(fileName))) {
            reader.beginArray();
            while (reader.hasNext()) {
                Game game = gson.fromJson(reader, Game.class);
                games.put(game.id, game);
            }
            reader.endArray();
            System.out.println("[SERVER] Database giochi caricato: " + games.size() + " partite.");
        } catch (IOException e) {
            System.err.println("[ERRORE] Impossibile trovare o leggere: " + fileName);
        }
    }
}
