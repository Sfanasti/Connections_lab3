import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/*
 Gestisce tutti gli utenti registrati: registrazione, login/logout,
 aggiornamento credenziali, statistiche e classifica.

 I dati vengono mantenuti in memoria in una ConcurrentHashMap e persistiti
 su file (players.json) ad ogni modifica tramite NIO.

 I metodi che accedono alla mappa players sono sincronizzati (synchronized)
 perché più thread del pool possono invocarli contemporaneamente.
 La ConcurrentHashMap garantisce la sicurezza delle singole operazioni atomiche,
 ma la sincronizzazione è necessaria per operazioni composte
 */
public class PlayerManager {
    private final Path path = Paths.get("players.json");
    private final Gson gson;
    private ConcurrentHashMap<String, Player> players;

    // Set dei giocatori attualmente connessi (username → presente = online)
    private final Set<String> onlinePlayers =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public PlayerManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.players = new ConcurrentHashMap<>();
        loadPlayers(); // carica il file players.json se esiste
    }

    /* Registra un nuovo utente. Restituisce false se il nome è già preso. */
    public synchronized boolean register(String name, String psw) {
        if (players.containsKey(name)) return false;

        players.put(name, new Player(name, psw));
        savePlayers();
        return true;
    }

    /*
     Autentica un utente e lo segna come online.
     Restituisce null se le credenziali sono errate o il giocatore è già connesso.
     */
    public Player login(String name, String psw) {
        if (onlinePlayers.contains(name)) {
            System.out.println("Login fallito: " + name + " è già online.");
            return null;
        }
        Player p = players.get(name);
        if (p != null && p.verifyPassword(psw)) {
            onlinePlayers.add(name);
            return p;
        }
        return null;
    }

    /* Segna il giocatore come offline. Restituisce false se non era online. */
    public boolean logout(String name) {
        return onlinePlayers.remove(name);
    }

    /*
     Aggiorna nome utente e/o password dopo verifica della vecchia password.

     Se newName è null/vuoto, il nome rimane invariato.
     Se newPsw è null/vuota, la password rimane invariata.
     Restituisce una stringa che descrive l'esito (SUCCESS o codice di errore).
     */
    public synchronized String updateCredentials(String oldName, String newName,
                                                  String oldPsw, String newPsw) {
        Player p = players.get(oldName);

        if (p == null) return "PLAYER_NOT_FOUND";
        if (!p.verifyPassword(oldPsw)) return "PLAYER_PASSWORD_ERROR";

        // Cambia il nome solo se newName è specificato e diverso dal vecchio
        if (newName != null && !newName.isEmpty() && !newName.equals(oldName)) {
            if (players.containsKey(newName)) return "PLAYER_ALREADY_EXISTS";

            players.remove(oldName);
            p.setName(newName);
            players.put(newName, p);

            // Se il giocatore è online, aggiorna il suo username anche nel set degli online
            if (onlinePlayers.remove(oldName)) {
                onlinePlayers.add(newName);
            }
        }

        // Cambia la password solo se newPsw è specificata e diversa dalla vecchia
        if (newPsw != null && !newPsw.isEmpty()) {
            if (newPsw.equals(oldPsw)) return "SAME_PASSWORD_ERROR";
            p.setPsw(newPsw);
        }

        savePlayers();
        return "SUCCESS";
    }

    /* Scrive tutti i giocatori su file JSON (sovrascrive il file precedente). */
    private synchronized void savePlayers() {
        try {
            String json = gson.toJson(players);
            Files.write(path, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ERRORE NIO]: impossibile salvare i dati " + e.getMessage());
        }
    }

    /* Legge i giocatori dal file JSON (all'avvio del server). */
    private void loadPlayers() {
        if (!Files.exists(path)) return;

        try {
            byte[] bytes = Files.readAllBytes(path);
            String json = new String(bytes, StandardCharsets.UTF_8);
            Type type = new TypeToken<ConcurrentHashMap<String, Player>>(){}.getType();
            ConcurrentHashMap<String, Player> map = gson.fromJson(json, type);

            if (map != null) this.players = map;
        } catch (IOException e) {
            System.err.println("[ERRORE NIO]: impossibile caricare i dati " + e.getMessage());
        }
    }

    public boolean isOnline(String name) {
        return onlinePlayers.contains(name);
    }

    /* Aggiorna le statistiche aggregate del giocatore e salva su file. */
    public synchronized void updatePlayerStats(String username, int mistakes,
                                               boolean won, boolean timedOut, int score) {
        Player p = players.get(username);
        if (p != null) {
            p.recordGame(mistakes, won, timedOut, score);
            savePlayers();
        }
    }

    /* Salva lo stato della partita corrente del giocatore (per ripristino al prossimo login). */
    public synchronized void savePlayerGameState(String username, int gameId, int mistakes,
                                                 boolean gameOver, java.util.Set<String> guessed) {
        Player p = players.get(username);
        if (p != null) {
            p.saveGameState(gameId, mistakes, gameOver, guessed);
            savePlayers();
        }
    }

    /* Azzera lo stato della partita corrente del giocatore (chiamato a fine partita). */
    public synchronized void clearAndSave(String username) {
        Player p = players.get(username);
        if (p != null) {
            p.clearGameState();
            savePlayers();
        }
    }

    /*
     Registra il timeout per tutti i giocatori offline che avevano una partita ancora aperta
     con l'ID passato come parametro.

     Va chiamato dal timer del server dopo aver già gestito i giocatori online.
     Aggiorna sia le statistiche aggregate del giocatore sia lo storico delle partite.

     gameId: ID della partita appena scaduta
     gm: GameManager per calcolare il punteggio parziale
     history: GameHistoryManager per registrare il risultato nello storico
     */
    public synchronized void recordTimeoutForOfflinePlayers(int gameId, GameManager gm,
                                                            GameHistoryManager history) {
        for (Player p : players.values()) {
            // Solo i giocatori che erano offline con questa partita ancora aperta
            if (p.getActiveGameId() == gameId && !p.isActiveGameOver()) {
                int partial = gm.score(
                        p.getActiveGuessedCategories().size(),
                        p.getActiveMistakes());

                p.recordGame(p.getActiveMistakes(), false, true, partial);

                history.recordResult(
                        gameId, p.getName(),
                        p.getActiveGuessedCategories().size(),
                        p.getActiveMistakes(),
                        partial, false, true);

                p.clearGameState();
            }
        }
        savePlayers();
    }

    /*
     Restituisce la classifica dei giocatori come stringa formattata.

     Se playerName è specificato restituisce solo il rank di quel giocatore.
     Se topK > 0 restituisce i primi K giocatori.
     Se topK == 0 restituisce tutti i giocatori ordinati per punteggio.
     */
    public synchronized String getLeaderboard(int topK, String playerName) {
        if (players.isEmpty()) return "NO_PLAYERS_REGISTERED";

        // Ordina per punteggio decrescente, poi per nome in caso di parità
        List<Player> allSorted = players.values().stream()
                .sorted(Comparator.comparingInt(Player::getTotalScore).reversed()
                        .thenComparing(Player::getName))
                .collect(Collectors.toList());

        // Caso 1: vuole il rank di uno specifico giocatore
        if (playerName != null && !playerName.isEmpty()) {
            for (int i = 0; i < allSorted.size(); i++) {
                if (allSorted.get(i).getName().equals(playerName)) {
                    return "RANK_INFO|" + playerName + "|#" + (i + 1)
                            + "|SCORE:" + allSorted.get(i).getTotalScore();
                }
            }
            return "PLAYER_NOT_FOUND";
        }

        // Caso 2: top-K o classifica completa

        int limit = (topK > 0) ? Math.min(topK, allSorted.size()) : allSorted.size();
        StringBuilder sb = new StringBuilder();
        sb.append(topK > 0 ? "TOP_" + limit + "_LEADERBOARD:\n" : "FULL_LEADERBOARD:\n");

        for (int i = 0; i < limit; i++) {
            Player p = allSorted.get(i);
            sb.append(i + 1).append(". ").append(p.getName())
                    .append(": ").append(p.getTotalScore()).append("\n");
        }

        return sb.toString();
    }

    public Player getPlayer(String name) {
        return players.get(name);
    }
}
